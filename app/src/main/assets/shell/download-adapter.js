/**
 * download-adapter.js — intercepts Blob downloads from the embedded PdfCraft
 * web app and forwards them to the Android host for durable staging and
 * destination export.
 *
 * This is a separate runtime adapter (NOT part of the upstream repository).
 * It does not edit generated JS/WASM, change the PDF algorithm, or modify
 * application behavior beyond capturing downloads before Blob URL revocation.
 *
 * Injected into the iframe at document-start (via WebViewCompat.addDocumentStartJavaScript)
 * with an iframe-load fallback in host.js. Self-exits on the host page.
 */
(function () {
    'use strict';

    const ASSET_ORIGIN = "https://appassets.androidplatform.net";

    // Only run inside the pdfcraft iframe, not on the host page.
    if (window.location.origin !== ASSET_ORIGIN) return;
    if (!window.location.pathname.startsWith("/assets/pdfcraft/")) return;

    // Idempotent installation guard. Both the Android document-start injection
    // and the host.js iframe-load fallback can execute this file; without this
    // guard each injection would add another layer of hooks and emit duplicate
    // exports for a single download click.
    if (window.__pdfCraftDownloadAdapterInstalled) return;
    window.__pdfCraftDownloadAdapterInstalled = true;

    let blobCounter = 0;
    const UNCLAIMED_TTL_MS = 60000; // 60s retention ceiling for unrevoked non-download blobs
    const REVOKE_GRACE_MS = 1000;   // 1s grace window if revokeObjectURL runs before click capture

    // originalUrl -> { blob: Blob, reading: boolean, revoked: boolean, ttlTimer: number }
    const blobMap = new Map();
    const processedClicks = new WeakSet();
    const originalCreateObjectURL = URL.createObjectURL;
    const originalRevokeObjectURL = URL.revokeObjectURL;

    function isBlobLike(obj) {
        if (!obj) return false;
        if (obj instanceof Blob) return true;
        const tag = Object.prototype.toString.call(obj);
        return tag === "[object Blob]" || tag === "[object File]";
    }

    function releaseEntry(url, shouldRevokeNative) {
        const entry = blobMap.get(url);
        if (!entry) return;
        if (entry.ttlTimer) clearTimeout(entry.ttlTimer);
        blobMap.delete(url);
        if (shouldRevokeNative) {
            try {
                originalRevokeObjectURL.call(URL, url);
            } catch (_e) {
                // Ignore revocation errors
            }
        }
    }

    // Return the real Blob URL so the upstream app's <a href> stays valid.
    URL.createObjectURL = function (obj) {
        const url = originalCreateObjectURL.call(this, obj);
        if (isBlobLike(obj) && typeof url === "string") {
            const ttlTimer = setTimeout(function () {
                const entry = blobMap.get(url);
                if (entry && !entry.reading) {
                    blobMap.delete(url);
                }
            }, UNCLAIMED_TTL_MS);
            blobMap.set(url, {
                blob: obj,
                reading: false,
                revoked: false,
                ttlTimer: ttlTimer
            });
        }
        return url;
    };

    // Defer native revocation if a download read is in flight, or give a brief
    // grace window before calling originalRevokeObjectURL so non-download Blobs
    // are properly freed in the browser engine.
    URL.revokeObjectURL = function (url) {
        const entry = blobMap.get(url);
        if (!entry) {
            return originalRevokeObjectURL.call(this, url);
        }
        entry.revoked = true;
        if (entry.reading) {
            // FileReader is actively reading this Blob; onload/onerror will revoke it.
            return;
        }
        setTimeout(function () {
            const current = blobMap.get(url);
            if (current && !current.reading) {
                releaseEntry(url, true);
            }
        }, REVOKE_GRACE_MS);
    };

    function captureAnchorDownload(anchor) {
        if (!anchor || typeof anchor.tagName !== "string") return false;
        if (anchor.tagName.toUpperCase() !== "A") return false;

        const href = anchor.getAttribute("href") || anchor.href || "";
        if (!href || !href.startsWith("blob:") || !blobMap.has(href)) return false;

        const entry = blobMap.get(href);
        if (!entry || entry.reading) return true; // Already being captured

        entry.reading = true;
        if (entry.ttlTimer) {
            clearTimeout(entry.ttlTimer);
            entry.ttlTimer = null;
        }

        const filename = anchor.getAttribute("download") || anchor.download || "download.pdf";
        const blob = entry.blob;
        const transferId = "blob_" + Date.now() + "_" + (++blobCounter);

        const reader = new FileReader();
        reader.onload = function () {
            const bytes = reader.result;
            try {
                if (window.parent && window.parent !== window) {
                    window.parent.postMessage({
                        type: "host:blobDownload",
                        transferId: transferId,
                        filename: filename,
                        mimeType: blob.type || "application/pdf",
                        bytes: bytes
                    }, ASSET_ORIGIN, [bytes]);
                }
            } finally {
                releaseEntry(href, true);
            }
        };
        reader.onerror = function () {
            releaseEntry(href, entry.revoked);
        };
        reader.readAsArrayBuffer(blob);
        return true;
    }

    // --- Intercept programmatic anchor.click() ---
    const originalClick = HTMLElement.prototype.click;
    HTMLElement.prototype.click = function () {
        if (this && !processedClicks.has(this)) {
            if (captureAnchorDownload(this)) {
                processedClicks.add(this);
                // Do not invoke originalClick on captured blob: download anchors,
                // preventing WebView's default broken blob: download navigation.
                return;
            }
        }
        return originalClick.call(this);
    };

    // --- Also intercept real DOM click events on <a href="blob:..." download> ---
    document.addEventListener("click", function (event) {
        const target = event.target && typeof event.target.closest === "function"
            ? event.target.closest("a")
            : null;
        if (target && !processedClicks.has(target)) {
            if (captureAnchorDownload(target)) {
                processedClicks.add(target);
                event.preventDefault();
                event.stopPropagation();
            }
        }
    }, true);

    // --- Cleanup on page unload ---
    window.addEventListener("beforeunload", function () {
        for (const [url] of blobMap.entries()) {
            releaseEntry(url, false);
        }
        blobMap.clear();
    });
})();
