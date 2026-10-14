/**
 * download-adapter.js — intercepts Blob downloads from the embedded PdfCraft
 * web app and forwards them to the Android host for durable staging and
 * destination export.
 *
 * This is a separate runtime adapter (NOT part of the upstream repository).
 * It does not edit generated JS/WASM, change the PDF algorithm, or modify
 * application behavior beyond capturing downloads before Blob URL revocation.
 *
 * Inject into the iframe by host.js after load — this script self-exits
 * when running on the host page (pathname starts with /assets/shell/).
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

    // --- Track Blob objects created by URL.createObjectURL ---
    // We keep references to prevent premature GC while FileReader reads asynchronously.
    const blobMap = new Map();        // originalUrl -> Blob
    const processedElements = new WeakSet();  // prevent duplicate processing per element
    const originalCreateObjectURL = URL.createObjectURL;
    const originalRevokeObjectURL = URL.revokeObjectURL;

    // Return the real Blob URL (not a fake replacement) so the upstream app's
    // <a href> stays valid. We just track the mapping internally.
    URL.createObjectURL = function (obj) {
        if (obj instanceof Blob) {
            const url = originalCreateObjectURL.call(this, obj);
            blobMap.set(url, obj);
            return url;
        }
        return originalCreateObjectURL.call(this, obj);
    };

    // Defer revocation until we've finished reading the Blob asynchronously.
    URL.revokeObjectURL = function (url) {
        if (blobMap.has(url)) {
            // Schedule cleanup: read is still in flight, revoke after
            setTimeout(() => { blobMap.delete(url); }, 5000);
            return;
        }
        return originalRevokeObjectURL.call(this, url);
    };

    // --- Intercept download anchor clicks via prototype override ---
    // The upstream web code does: create Blob URL, set anchor.href,
    // anchor.click(), revoke URL. We intercept click() to capture the Blob.
    const originalClick = HTMLElement.prototype.click;
    HTMLElement.prototype.click = function () {
        if ((this.tagName === "A" || this.tagName === "a") && !processedElements.has(this)) {
            const href = this.getAttribute("href");
            if (href && href.startsWith("blob:") && blobMap.has(href)) {
                processedElements.add(this);
                const blob = blobMap.get(href);
                const filename = this.getAttribute("download") || "download.pdf";

                const reader = new FileReader();
                reader.onload = function () {
                    const bytes = reader.result;
                    if (window.parent && window.parent.location.origin === ASSET_ORIGIN) {
                        window.parent.postMessage({
                            type: "host:blobDownload",
                            transferId: "blob_" + Date.now() + "_" + Math.random().toString(36).slice(2, 11),
                            filename: filename,
                            mimeType: blob.type || "application/pdf",
                            bytes: bytes
                        }, ASSET_ORIGIN, [bytes]);
                    }
                    // Release the retained Blob reference
                    blobMap.delete(href);
                    originalRevokeObjectURL.call(URL, href);
                };
                reader.onerror = function () {
                    blobMap.delete(href);
                };
                reader.readAsArrayBuffer(blob);
            }
        }
        return originalClick.call(this);
    };

    // --- Cleanup on page unload ---
    window.addEventListener("beforeunload", function () {
        blobMap.clear();
    });
})();
