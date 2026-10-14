/**
 * host.js — Same-origin bridge between the Android shell and the embedded PdfCraft web app.
 *
 * Architecture (per plan §5):
 *   PdfCraft iframe → (postMessage) → host.js → (WebMessageListener.postMessage) → Android
 *   Android → (WebViewCompat.postWebMessage) → host.js → (postMessage) → PdfCraft iframe
 *
 * Protocol — all messages are JSON strings (simplest reliable path):
 *   Web → Android:  {"type":"host:ready","version":"x"}
 *                    {"type":"host:save","transferId":"t0","name":"doc.pdf","saveAs":true,"bytes":"<base64>"}
 *                    {"type":"host:dirty","dirty":true}
 *                    {"type":"host:failed","error":"msg"}
 *                    {"type":"host:saveChunk","transferId":"t0","name":"doc.pdf","saveAs":true,"totalBytes":N,"seq":i,"totalChunks":N,"data":"<base64 chunk>"}
 *                    {"type":"host:saveComplete","transferId":"t0","name":"doc.pdf","saveAs":true,"totalBytes":N}
 *   Android → Web:  {"type":"host:import","name":"doc.pdf","bytes":"<base64>"}
 *                    {"type":"host:importChunk","transferId":"imp1","name":"doc.pdf","totalBytes":N,"seq":i,"totalChunks":N,"data":"<base64 chunk>"}
 *
 * Chunk size: 256 KiB — keeps individual postMessage payloads well under IPC limits.
 */
(function () {
    'use strict';

    const ASSET_ORIGIN = "https://appassets.androidplatform.net";
    const CHUNK_SIZE = 256 * 1024; // 256 KiB per plan §5

    const frame = document.getElementById("pdfcraft-frame");
    let appReady = false;
    let pendingImports = [];
    let importChunks = new Map(); // transferId -> {name, chunks[], totalChunks}

    // ─── Single message listener handling BOTH iframe→host and Android→host ──
    window.addEventListener("message", function (event) {
        if (event.origin !== ASSET_ORIGIN) return;

        const data = event.data;
        if (!data || typeof data.type !== "string") return;

        // --- Messages FROM the PdfCraft iframe ---
        if (event.source === frame.contentWindow) {
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
            }
            return;
        }

        // --- Messages FROM the Android host (via postWebMessage) ---
        // event.source === window
        switch (data.type) {
            case "host:import":
                if (appReady) {
                    sendOpenToIframe(data.name, base64ToArrayBuffer(data.bytes));
                } else {
                    pendingImports.push(data);
                }
                break;

            case "host:importChunk":
                handleImportChunk(data.transferId, data.name, data.totalBytes, data.seq, data.totalChunks, data.data);
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

    // ─── Send to Android via WebMessageListener ────────────────────────
    function sendToNative(message) {
        if (window.WebMessageListener && typeof window.WebMessageListener.postMessage === "function") {
            window.WebMessageListener.postMessage(JSON.stringify(message));
            return true;
        }
        console.warn("WebMessageListener not available", message);
        return false;
    }

    // ─── Send pdfcraft:open to the iframe with binary bytes ─────────────
    function sendOpenToIframe(name, buffer) {
        if (!buffer) {
            console.error("host:import: invalid bytes for", name);
            return;
        }
        frame.contentWindow.postMessage(
            { type: "pdfcraft:open", name: name, bytes: buffer },
            ASSET_ORIGIN,
            [buffer]
        );
    }

    function flushQueuedImports() {
        if (!appReady || pendingImports.length === 0) return;
        const queued = pendingImports.splice(0);
        for (const msg of queued) {
            sendOpenToIframe(msg.name, base64ToArrayBuffer(msg.bytes));
        }
    }

    function handleImportChunk(transferId, name, totalBytes, seq, totalChunks, base64Data) {
        let entry = importChunks.get(transferId);
        if (!entry) {
            entry = { name: name, chunks: new Array(totalChunks), received: 0, totalBytes: totalBytes };
            importChunks.set(transferId, entry);
        }
        if (seq < entry.chunks.length) {
            entry.chunks[seq] = base64ToArrayBuffer(base64Data);
            entry.received++;
        }
        if (entry.received >= totalChunks) {
            importChunks.delete(transferId);
            const full = concatenate(entry.chunks);
            sendOpenToIframe(entry.name, full);
        }
    }

    // ─── Handle pdfcraft:save from the iframe ──────────────────────────
    function handleSaveToNative(data) {
        const id = "t" + Date.now();
        const name = data.name || "document.pdf";
        const saveAs = !!data.saveAs;
        const buffer = toArrayBuffer(data.bytes);
        if (!buffer) {
            console.error("host:save: invalid bytes payload");
            return;
        }

        if (buffer.byteLength <= CHUNK_SIZE) {
            // Single message — base64-encode the bytes inline
            sendToNative({
                type: "host:save",
                transferId: id,
                name: name,
                saveAs: saveAs,
                bytes: arrayBufferToBase64(buffer)
            });
        } else {
            // Chunked transfer
            const totalChunks = Math.ceil(buffer.byteLength / CHUNK_SIZE);
            for (let i = 0; i < totalChunks; i++) {
                const start = i * CHUNK_SIZE;
                const end = Math.min(start + CHUNK_SIZE, buffer.byteLength);
                sendToNative({
                    type: "host:saveChunk",
                    transferId: id,
                    name: name,
                    saveAs: saveAs,
                    totalBytes: buffer.byteLength,
                    seq: i,
                    totalChunks: totalChunks,
                    data: arrayBufferToBase64(buffer.slice(start, end))
                });
            }
            sendToNative({
                type: "host:saveComplete",
                transferId: id,
                name: name,
                saveAs: saveAs,
                totalBytes: buffer.byteLength
            });
        }
    }

    // ─── Utilities ────────────────────────────────────────────────────────
    function toArrayBuffer(input) {
        if (input instanceof ArrayBuffer) return input;
        if (input instanceof Uint8Array) return input.buffer;
        return null;
    }

    function base64ToArrayBuffer(b64) {
        const binary = atob(b64);
        const len = binary.length;
        const bytes = new Uint8Array(len);
        for (let i = 0; i < len; i++) bytes[i] = binary.charCodeAt(i);
        return bytes.buffer;
    }

    function arrayBufferToBase64(buf) {
        let binary = "";
        const bytes = new Uint8Array(buf);
        const len = bytes.byteLength;
        for (let i = 0; i < len; i++) binary += String.fromCharCode(bytes[i]);
        return btoa(binary);
    }

    function concatenate(chunks) {
        const total = chunks.reduce((s, c) => s + c.byteLength, 0);
        const result = new Uint8Array(total);
        let off = 0;
        for (const c of chunks) { result.set(new Uint8Array(c), off); off += c.byteLength; }
        return result.buffer;
    }

    // Expose for download-adapter.js
    window.PdfCraftHost = {
        ASSET_ORIGIN: ASSET_ORIGIN,
        sendToNative: sendToNative,
        handleSave: handleSaveToNative,
        sendOpenToIframe: sendOpenToIframe,
        flushQueuedImports: flushQueuedImports
    };
})();
