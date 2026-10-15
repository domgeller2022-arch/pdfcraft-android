/**
 * host.js — Same-origin bridge between the Android shell and the embedded PdfCraft web app.
 *
 * Architecture (per plan §5):
 *   PdfCraft iframe → (postMessage) → host.js → (WebMessageListener.postMessage) → Android
 *   Android → (WebViewCompat.postWebMessage) → host.js → (postMessage) → PdfCraft iframe
 *
 * Chunk size: 256 KiB — keeps individual postMessage payloads well under IPC limits.
 * Includes independent SHA-256 sender checksums, sequence/metadata validation, bounded
 * transfer queues, timeouts, and chunk-acknowledgement backpressure.
 */
(function () {
    'use strict';

    const ASSET_ORIGIN = "https://appassets.androidplatform.net";
    const CHUNK_SIZE = 256 * 1024; // 256 KiB per plan §5
    const MAX_IMPORT_BYTES = 100 * 1024 * 1024; // 100 MiB phone-safe import ceiling
    const MAX_CONCURRENT_IMPORTS = 4;
    const TRANSFER_TIMEOUT_MS = 60000; // 60s timeout for incomplete chunked imports

    // Tunable transfer limits. maxExportBytes is aligned with the native
    // ExportQueue.MAX_STAGING_BYTES (256 MiB); an export above any limit is refused
    // and retained within the retry memory budget; otherwise a visible error tells
    // the user to keep the document open and save again.
    const config = {
        maxExportBytes: 256 * 1024 * 1024,
        maxQueuedExportBytes: 256 * 1024 * 1024,
        maxRetainedExportBytes: 256 * 1024 * 1024,
        // Receiver-controlled backpressure: if the native host does not acknowledge a
        // chunk within this window, the transfer fails, is retained, and is surfaced.
        ackTimeoutMs: 30000
    };

    const frame = document.getElementById("pdfcraft-frame");
    let appReady = false;
    let pendingImports = [];
    let importChunks = new Map(); // transferId -> {name, chunks[], expectedSeq, receivedBytes, totalBytes, totalChunks, expectedSha256, startedAt}

    // Outbound chunked export backpressure queue.
    // Bounded by total bytes (not an arbitrary job count) so a batch of modest
    // multi-chunk exports is never silently dropped.
    let outboundExports = [];
    let activeOutbound = null;
    let queuedOutboundBytes = 0;
    // Exports that could not be queued/sent (oversize, byte-bound exceeded, ack timeout,
    // native reject). Retained in memory so the user can retry, and surfaced to Android.
    const failedExports = new Map(); // transferId -> {name, saveAs, mimeType, buffer, reason}

    // Monotonic counter for save IDs — avoids Date.now() collisions when multiple
    // saves happen in the same millisecond.
    let saveCounter = 0;

    // ─── Inject download-adapter.js into the iframe (fallback) ─────────
    function injectDownloadAdapter() {
        try {
            if (!frame) return;
            const doc = frame.contentDocument;
            if (!doc || !doc.head) return;
            if (doc.querySelector('script[src="/assets/shell/download-adapter.js"]')) return;
            const script = doc.createElement('script');
            script.src = '/assets/shell/download-adapter.js';
            doc.head.appendChild(script);
        } catch (e) {
            console.warn('Failed to inject download adapter into iframe:', e);
        }
    }

    if (frame) {
        frame.addEventListener('load', injectDownloadAdapter);
        frame.addEventListener('DOMContentLoaded', function () {
            requestAnimationFrame(injectDownloadAdapter);
        });
    }

    // ─── Single message listener handling BOTH iframe→host and Android→host ──
    window.addEventListener("message", function (event) {
        const isFromIframe = frame && event.source === frame.contentWindow;

        if (isFromIframe) {
            if (event.origin !== ASSET_ORIGIN) return;

            let data = event.data;
            if (!data || typeof data.type !== "string") return;

            switch (data.type) {
                case "pdfcraft:ready":
                    appReady = true;
                    sendToNative({ type: "host:ready", version: data.version || "unknown" });
                    flushQueuedImports();
                    break;

                case "pdfcraft:save":
                    handleSaveToNative(data);
                    break;

                case "pdfcraft:dirty":
                    sendToNative({ type: "host:dirty", dirty: !!data.dirty });
                    break;

                case "pdfcraft:failed":
                    sendToNative({ type: "host:failed", error: data.error || "unknown error" });
                    break;

                case "host:blobDownload":
                    handleBlobDownloadFromIframe(data);
                    break;
            }
            return;
        }

        // --- Messages FROM the Android host (via WebViewCompat.postWebMessage) ---
        if (event.origin !== ASSET_ORIGIN && event.origin !== "") return;
        if (event.source !== null && event.source !== window) return;
        if (typeof event.data !== "string") return;

        let data;
        try {
            data = JSON.parse(event.data);
        } catch (e) {
            console.warn("Ignoring unparseable native message string:", event.data);
            return;
        }
        if (!data || typeof data.type !== "string") return;

        switch (data.type) {
            case "host:import":
                handleSingleImport(data);
                break;

            case "host:importChunk":
                handleImportChunk(
                    data.transferId,
                    data.name,
                    data.totalBytes,
                    data.seq,
                    data.totalChunks,
                    data.data,
                    data.sha256
                );
                break;

            case "host:saveChunkAck":
                handleSaveChunkAck(data.transferId, data.seq, data.success, data.error);
                break;

            case "host:retryExport":
                retryFailedExport(data.transferId);
                break;

            case "host:reviewFailedExport":
                reviewFailedExport();
                break;

            case "host:discardExport":
                discardFailedExport(data.transferId);
                break;

            case "host:saveResult":
                if (data.success) {
                    console.log("Save completed:", data.transferId);
                } else {
                    console.error("Save failed:", data.transferId, data.error || "unknown");
                }
                break;
        }
    });

    // Assign deferred iframe src only after message listener is registered.
    if (frame) {
        const deferredSrc = frame.getAttribute("data-src");
        const currentSrc = frame.getAttribute("src");
        if (deferredSrc && (!currentSrc || currentSrc === "")) {
            frame.setAttribute("src", deferredSrc);
        }
    }

    function sendToNative(message) {
        if (window.WebMessageListener && typeof window.WebMessageListener.postMessage === "function") {
            window.WebMessageListener.postMessage(JSON.stringify(message));
            return true;
        }
        console.warn("WebMessageListener not available", message);
        return false;
    }

    function sendOpenToIframe(name, buffer) {
        if (!buffer) {
            console.error("host:import: invalid bytes for", name);
            return;
        }
        if (!frame || !frame.contentWindow) {
            console.error("host:import: iframe contentWindow unavailable");
            return;
        }
        frame.contentWindow.postMessage(
            { type: "pdfcraft:open", name: name || "document.pdf", bytes: buffer },
            ASSET_ORIGIN,
            [buffer]
        );
    }

    function handleSingleImport(data) {
        const name = (typeof data.name === "string" && data.name.length > 0) ? data.name : "document.pdf";
        const buffer = base64ToArrayBuffer(data.bytes);
        if (!buffer) {
            console.error("host:import: missing or invalid base64 payload for", name);
            return;
        }
        if (buffer.byteLength > MAX_IMPORT_BYTES) {
            console.error("host:import: payload exceeds MAX_IMPORT_BYTES:", buffer.byteLength);
            return;
        }
        if (typeof data.totalBytes === "number" && buffer.byteLength !== data.totalBytes) {
            console.error("host:import: size mismatch: expected", data.totalBytes, "got", buffer.byteLength);
            return;
        }
        if (typeof data.sha256 === "string" && data.sha256.length > 0) {
            const actualHash = sha256Hex(buffer);
            if (actualHash !== data.sha256.toLowerCase()) {
                console.error("host:import: SHA-256 mismatch for", name);
                return;
            }
        }
        if (appReady) {
            sendOpenToIframe(name, buffer);
        } else {
            pendingImports.push({ name: name, buffer: buffer });
        }
    }

    function flushQueuedImports() {
        if (!appReady || pendingImports.length === 0) return;
        const queued = pendingImports.splice(0);
        for (const msg of queued) {
            const buffer = msg.buffer || base64ToArrayBuffer(msg.bytes);
            sendOpenToIframe(msg.name, buffer);
        }
    }

    function evictStaleImports(now) {
        for (const [id, entry] of importChunks.entries()) {
            if (now - entry.startedAt > TRANSFER_TIMEOUT_MS) {
                console.warn("Evicting timed-out import transfer:", id);
                importChunks.delete(id);
            }
        }
    }

    function handleImportChunk(transferId, name, totalBytes, seq, totalChunks, base64Data, expectedSha256) {
        const now = Date.now();
        evictStaleImports(now);

        if (typeof transferId !== "string" || transferId.length === 0) return;
        if (!Number.isInteger(seq) || !Number.isInteger(totalChunks) || totalChunks <= 0) {
            importChunks.delete(transferId);
            return;
        }
        if (seq < 0 || seq >= totalChunks) {
            console.error("host:importChunk: out-of-range seq", seq, "of", totalChunks);
            importChunks.delete(transferId);
            return;
        }
        if (!Number.isInteger(totalBytes) || totalBytes <= 0 || totalBytes > MAX_IMPORT_BYTES) {
            console.error("host:importChunk: invalid or oversized totalBytes:", totalBytes);
            importChunks.delete(transferId);
            return;
        }

        const resolvedName = (typeof name === "string" && name.length > 0) ? name : "document.pdf";
        let entry = importChunks.get(transferId);

        if (!entry) {
            if (seq !== 0) {
                console.error("host:importChunk: expected initial seq=0, got", seq, "for", transferId);
                return;
            }
            if (importChunks.size >= MAX_CONCURRENT_IMPORTS) {
                console.error("host:importChunk: too many concurrent imports, rejecting", transferId);
                return;
            }
            entry = {
                name: resolvedName,
                chunks: new Array(totalChunks),
                expectedSeq: 0,
                receivedBytes: 0,
                totalBytes: totalBytes,
                totalChunks: totalChunks,
                expectedSha256: (typeof expectedSha256 === "string" && expectedSha256.length > 0)
                    ? expectedSha256.toLowerCase()
                    : null,
                startedAt: now
            };
            importChunks.set(transferId, entry);
        } else {
            // Validate strict metadata consistency and sequential ordering across chunks
            if (entry.name !== resolvedName ||
                entry.totalBytes !== totalBytes ||
                entry.totalChunks !== totalChunks ||
                (expectedSha256 && entry.expectedSha256 && entry.expectedSha256 !== expectedSha256.toLowerCase())) {
                console.error("host:importChunk: inconsistent metadata for", transferId);
                importChunks.delete(transferId);
                return;
            }
            if (seq !== entry.expectedSeq) {
                console.error("host:importChunk: duplicate or out-of-order seq", seq, "expected", entry.expectedSeq, "for", transferId);
                importChunks.delete(transferId);
                return;
            }
        }

        const chunkBuffer = base64ToArrayBuffer(base64Data);
        if (!chunkBuffer || chunkBuffer.byteLength === 0) {
            console.error("host:importChunk: invalid chunk data for", transferId, "seq", seq);
            importChunks.delete(transferId);
            return;
        }
        if (entry.receivedBytes + chunkBuffer.byteLength > entry.totalBytes) {
            console.error("host:importChunk: received bytes exceed declared totalBytes for", transferId);
            importChunks.delete(transferId);
            return;
        }

        entry.chunks[seq] = chunkBuffer;
        entry.receivedBytes += chunkBuffer.byteLength;
        entry.expectedSeq = seq + 1;

        if (entry.expectedSeq === totalChunks) {
            importChunks.delete(transferId);
            if (entry.receivedBytes !== entry.totalBytes) {
                console.error("host:importChunk: final byte count mismatch: expected", entry.totalBytes, "got", entry.receivedBytes);
                return;
            }
            const full = concatenate(entry.chunks);
            if (full.byteLength !== entry.totalBytes) {
                console.error("host:importChunk: concatenated size mismatch");
                return;
            }
            if (entry.expectedSha256) {
                const actualHash = sha256Hex(full);
                if (actualHash !== entry.expectedSha256) {
                    console.error("host:importChunk: SHA-256 mismatch: expected", entry.expectedSha256, "got", actualHash);
                    return;
                }
            }
            if (appReady) {
                sendOpenToIframe(entry.name, full);
            } else {
                pendingImports.push({ name: entry.name, buffer: full });
            }
        }
    }

    // ─── Handle pdfcraft:save and host:blobDownload from the iframe ────
    function generateSaveId(prefix) {
        return (prefix || "t") + Date.now() + "_" + (++saveCounter);
    }

    function inferMimeType(filename, explicitMime) {
        if (explicitMime && explicitMime !== "application/octet-stream") {
            const ext = (filename || "").split('.').pop().toLowerCase();
            const byExt = mimeFromExtension(ext);
            if (byExt && byExt !== "application/pdf" && explicitMime === "application/pdf") {
                return byExt;
            }
            return explicitMime;
        }
        const ext = (filename || "").split('.').pop().toLowerCase();
        return mimeFromExtension(ext) || "application/pdf";
    }

    function mimeFromExtension(ext) {
        switch (ext) {
            case "pdf":
            case "pdfa":
            case "pdfx":
            case "pdfua":
                return "application/pdf";
            case "png":
                return "image/png";
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "gif":
                return "image/gif";
            case "webp":
                return "image/webp";
            case "svg":
                return "image/svg+xml";
            case "txt":
            case "text":
                return "text/plain";
            case "csv":
                return "text/csv";
            case "json":
                return "application/json";
            case "xfdf":
                return "application/vnd.adobe.xfdf";
            case "xml":
                return "application/xml";
            default:
                return null;
        }
    }

    function transferBytesToNative(id, name, saveAs, mimeType, buffer, singleMessageType) {
        if (buffer.byteLength > config.maxExportBytes) {
            retainFailedExport(id, name, saveAs, mimeType, buffer, "oversize",
                "Export is larger than the " + Math.round(config.maxExportBytes / (1024 * 1024)) +
                " MiB device limit");
            return;
        }
        const senderSha256 = sha256Hex(buffer);

        if (buffer.byteLength <= CHUNK_SIZE) {
            sendToNative({
                type: singleMessageType || "host:save",
                transferId: id,
                name: name,
                filename: name,
                saveAs: saveAs,
                mimeType: mimeType,
                totalBytes: buffer.byteLength,
                sha256: senderSha256,
                bytes: arrayBufferToBase64(buffer)
            });
            return;
        }

        if (queuedOutboundBytes + buffer.byteLength > config.maxQueuedExportBytes) {
            retainFailedExport(id, name, saveAs, mimeType, buffer, "queue-full",
                "Too many exports are already waiting; this one was retained so you can retry it");
            return;
        }

        const totalChunks = Math.ceil(buffer.byteLength / CHUNK_SIZE);
        outboundExports.push({
            transferId: id,
            name: name,
            saveAs: saveAs,
            mimeType: mimeType,
            buffer: buffer,
            totalBytes: buffer.byteLength,
            totalChunks: totalChunks,
            sha256: senderSha256,
            nextSeq: 0,
            ackTimer: null
        });
        queuedOutboundBytes += buffer.byteLength;
        notifyPendingWork();
        if (!activeOutbound) {
            pumpNextOutboundTransfer();
        }
    }

    // Retain a refused/failed export and let Android surface a visible retry workflow.
    // Nothing is dropped silently: every refusal produces a host:saveFailed message.
    function retainFailedExport(id, name, saveAs, mimeType, buffer, reason, message) {
        const retainedBytes = Array.from(failedExports.values()).reduce((sum, tx) => sum + (tx.buffer ? tx.buffer.byteLength : 0), 0);
        const retained = buffer.byteLength <= config.maxRetainedExportBytes && retainedBytes + buffer.byteLength <= config.maxRetainedExportBytes;
        const error = retained ? message : message + ". Retry data could not be retained within the memory limit. Keep the document open and save again after resolving the error.";
        // Keep a tiny failure record even when bytes cannot be retained. Back must
        // still warn, because upstream may already have cleared its dirty flag.
        failedExports.set(id, {
            transferId: id, name: name, saveAs: saveAs, mimeType: mimeType,
            buffer: retained ? buffer : null, reason: reason, error: error
        });
        console.error(retained ? "Export retained for retry:" : "Export not retained:", id, reason, "-", message);
        sendToNative({
            type: "host:saveFailed",
            transferId: id,
            name: name,
            reason: reason,
            retained: retained,
            error: error
        });
        notifyPendingWork();
    }

    // Report host-side pending work (waiting + active + retained) so the Android exit
    // policy can account for transfers that have not reached native staging yet.
    function notifyPendingWork() {
        sendToNative({
            type: "host:pendingWork",
            queued: outboundExports.length,
            active: activeOutbound ? 1 : 0,
            failed: failedExports.size,
            queuedBytes: queuedOutboundBytes
        });
    }

    function getPendingWork() {
        return {
            queued: outboundExports.length,
            active: activeOutbound ? 1 : 0,
            failed: failedExports.size,
            queuedBytes: queuedOutboundBytes
        };
    }

    function pumpNextOutboundTransfer() {
        if (activeOutbound) return;
        activeOutbound = outboundExports.shift() || null;
        if (!activeOutbound) {
            notifyPendingWork();
            return;
        }
        queuedOutboundBytes = Math.max(0, queuedOutboundBytes - activeOutbound.totalBytes);
        sendCurrentOutboundChunk();
    }

    function sendCurrentOutboundChunk() {
        if (!activeOutbound) return;
        const tx = activeOutbound;
        const seq = tx.nextSeq;
        const start = seq * CHUNK_SIZE;
        const end = Math.min(start + CHUNK_SIZE, tx.totalBytes);
        const slice = tx.buffer.slice(start, end);

        sendToNative({
            type: "host:saveChunk",
            transferId: tx.transferId,
            name: tx.name,
            saveAs: tx.saveAs,
            mimeType: tx.mimeType,
            totalBytes: tx.totalBytes,
            seq: seq,
            totalChunks: tx.totalChunks,
            chunkLength: slice.byteLength,
            data: arrayBufferToBase64(slice)
        });

        clearOutboundAckTimer(tx);
        // Receiver-controlled backpressure: advance ONLY on a matching native ack.
        // If none arrives within config.ackTimeoutMs the transfer fails, is retained,
        // and is surfaced to the user — it never silently advances.
        tx.ackTimer = setTimeout(function () {
            if (activeOutbound === tx && tx.nextSeq === seq) {
                failOutboundTransfer(tx, "ack-timeout",
                    "No acknowledgement for chunk " + seq + " within " + config.ackTimeoutMs + " ms");
            }
        }, config.ackTimeoutMs);
    }

    function clearOutboundAckTimer(tx) {
        if (tx && tx.ackTimer) {
            clearTimeout(tx.ackTimer);
            tx.ackTimer = null;
        }
    }

    function failOutboundTransfer(tx, reason, message) {
        clearOutboundAckTimer(tx);
        if (activeOutbound === tx) activeOutbound = null;
        retainFailedExport(tx.transferId, tx.name, tx.saveAs, tx.mimeType, tx.buffer, reason, message);
        pumpNextOutboundTransfer();
    }

    function handleSaveChunkAck(transferId, seq, success, error) {
        // Only an ack naming the active transfer AND the expected sequence may change state.
        if (!activeOutbound || activeOutbound.transferId !== transferId) return;
        if (seq !== activeOutbound.nextSeq) return;
        clearOutboundAckTimer(activeOutbound);
        if (success === false) {
            failOutboundTransfer(activeOutbound, "native-reject",
                "Native rejected chunk " + seq + ": " + (error || ""));
            return;
        }
        advanceOutboundChunk(transferId, seq);
    }

    function advanceOutboundChunk(transferId, seq) {
        if (!activeOutbound || activeOutbound.transferId !== transferId) return;
        const tx = activeOutbound;
        clearOutboundAckTimer(tx);
        tx.nextSeq = seq + 1;

        if (tx.nextSeq < tx.totalChunks) {
            sendCurrentOutboundChunk();
        } else {
            sendToNative({
                type: "host:saveComplete",
                transferId: tx.transferId,
                name: tx.name,
                saveAs: tx.saveAs,
                mimeType: tx.mimeType,
                totalBytes: tx.totalBytes,
                totalChunks: tx.totalChunks,
                sha256: tx.sha256
            });
            activeOutbound = null;
            notifyPendingWork();
            pumpNextOutboundTransfer();
        }
    }

    function retryFailedExport(transferId) {
        const entry = failedExports.get(transferId);
        if (!entry || !entry.buffer) return;
        failedExports.delete(transferId);
        // Re-send under a fresh id so native per-transfer staging state starts clean.
        transferBytesToNative(generateSaveId("retry_"), entry.name, entry.saveAs,
            entry.mimeType, entry.buffer, "host:save");
        notifyPendingWork();
    }

    function reviewFailedExport() {
        const entry = failedExports.values().next().value;
        if (!entry) return;
        sendToNative({type: "host:saveFailed", transferId: entry.transferId,
            name: entry.name, reason: entry.reason, error: entry.error,
            retained: !!entry.buffer});
    }

    function discardFailedExport(transferId) {
        failedExports.delete(transferId);
        notifyPendingWork();
    }

    function handleSaveToNative(data) {
        const id = generateSaveId("t");
        const name = data.name || "document.pdf";
        const saveAs = !!data.saveAs;
        const mimeType = inferMimeType(name, data.mimeType);
        const buffer = toArrayBuffer(data.bytes);
        if (!buffer) {
            console.error("host:save: invalid bytes payload");
            return;
        }
        transferBytesToNative(id, name, saveAs, mimeType, buffer, "host:save");
    }

    function handleBlobDownloadFromIframe(data) {
        const buffer = toArrayBuffer(data.bytes);
        if (!buffer) {
            console.error("host:blobDownload: invalid bytes payload");
            return;
        }
        const id = data.transferId || generateSaveId("blob_");
        const name = data.filename || data.name || "download.pdf";
        const mimeType = inferMimeType(name, data.mimeType);
        transferBytesToNative(id, name, true, mimeType, buffer, "host:blobDownload");
    }

    // ─── Utilities ────────────────────────────────────────────────────────
    function toArrayBuffer(input) {
        if (!input) return null;
        if (Object.prototype.toString.call(input) === "[object ArrayBuffer]") return input;
        if (ArrayBuffer.isView(input)) {
            if (input.byteOffset === 0 && input.byteLength === input.buffer.byteLength) {
                return input.buffer;
            }
            return input.buffer.slice(input.byteOffset, input.byteOffset + input.byteLength);
        }
        return null;
    }

    function base64ToArrayBuffer(b64) {
        if (typeof b64 !== "string" || b64.length === 0) return null;
        try {
            const binary = atob(b64);
            const len = binary.length;
            const bytes = new Uint8Array(len);
            for (let i = 0; i < len; i++) bytes[i] = binary.charCodeAt(i);
            return bytes.buffer;
        } catch (_e) {
            return null;
        }
    }

    function arrayBufferToBase64(buf) {
        const bytes = new Uint8Array(buf);
        const len = bytes.byteLength;
        const STEP = 0x8000;
        const parts = [];
        for (let i = 0; i < len; i += STEP) {
            const sub = bytes.subarray(i, Math.min(i + STEP, len));
            parts.push(String.fromCharCode.apply(null, sub));
        }
        return btoa(parts.join(""));
    }

    function concatenate(chunks) {
        const total = chunks.reduce((s, c) => s + (c ? c.byteLength : 0), 0);
        const result = new Uint8Array(total);
        let off = 0;
        for (const c of chunks) {
            if (!c) continue;
            result.set(new Uint8Array(c), off);
            off += c.byteLength;
        }
        return result.buffer;
    }

    // Synchronous FIPS 180-4 SHA-256 implementation over an ArrayBuffer
    const SHA256_K = new Uint32Array([
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
    ]);

    function sha256Hex(buffer) {
        const bytes = new Uint8Array(buffer);
        const bitLenHi = Math.floor((bytes.length * 8) / 0x100000000);
        const bitLenLo = (bytes.length * 8) >>> 0;
        const padLen = ((bytes.length + 9 + 63) & ~63);
        const padded = new Uint8Array(padLen);
        padded.set(bytes);
        padded[bytes.length] = 0x80;
        const view = new DataView(padded.buffer);
        view.setUint32(padLen - 8, bitLenHi, false);
        view.setUint32(padLen - 4, bitLenLo, false);

        let h0 = 0x6a09e667, h1 = 0xbb67ae85, h2 = 0x3c6ef372, h3 = 0xa54ff53a;
        let h4 = 0x510e527f, h5 = 0x9b05688c, h6 = 0x1f83d9ab, h7 = 0x5be0cd19;
        const w = new Uint32Array(64);

        for (let off = 0; off < padLen; off += 64) {
            for (let i = 0; i < 16; i++) {
                w[i] = view.getUint32(off + i * 4, false);
            }
            for (let i = 16; i < 64; i++) {
                const v1 = w[i - 15];
                const s0 = ((v1 >>> 7) | (v1 << 25)) ^ ((v1 >>> 18) | (v1 << 14)) ^ (v1 >>> 3);
                const v2 = w[i - 2];
                const s1 = ((v2 >>> 17) | (v2 << 15)) ^ ((v2 >>> 19) | (v2 << 13)) ^ (v2 >>> 10);
                w[i] = (w[i - 16] + s0 + w[i - 7] + s1) >>> 0;
            }

            let a = h0, b = h1, c = h2, d = h3, e = h4, f = h5, g = h6, h = h7;
            for (let i = 0; i < 64; i++) {
                const S1 = ((e >>> 6) | (e << 26)) ^ ((e >>> 11) | (e << 21)) ^ ((e >>> 25) | (e << 7));
                const ch = (e & f) ^ (~e & g);
                const temp1 = (h + S1 + ch + SHA256_K[i] + w[i]) >>> 0;
                const S0 = ((a >>> 2) | (a << 30)) ^ ((a >>> 13) | (a << 19)) ^ ((a >>> 22) | (a << 10));
                const maj = (a & b) ^ (a & c) ^ (b & c);
                const temp2 = (S0 + maj) >>> 0;

                h = g; g = f; f = e;
                e = (d + temp1) >>> 0;
                d = c; c = b; b = a;
                a = (temp1 + temp2) >>> 0;
            }

            h0 = (h0 + a) >>> 0; h1 = (h1 + b) >>> 0; h2 = (h2 + c) >>> 0; h3 = (h3 + d) >>> 0;
            h4 = (h4 + e) >>> 0; h5 = (h5 + f) >>> 0; h6 = (h6 + g) >>> 0; h7 = (h7 + h) >>> 0;
        }

        return [h0, h1, h2, h3, h4, h5, h6, h7]
            .map(v => v.toString(16).padStart(8, "0"))
            .join("");
    }

    window.PdfCraftHost = {
        ASSET_ORIGIN: ASSET_ORIGIN,
        config: config,
        sendToNative: sendToNative,
        handleSave: handleSaveToNative,
        handleBlobDownload: handleBlobDownloadFromIframe,
        sendOpenToIframe: sendOpenToIframe,
        flushQueuedImports: flushQueuedImports,
        generateSaveId: generateSaveId,
        toArrayBuffer: toArrayBuffer,
        sha256Hex: sha256Hex,
        retryFailedExport: retryFailedExport,
        discardFailedExport: discardFailedExport,
        getPendingWork: getPendingWork
    };
})();
