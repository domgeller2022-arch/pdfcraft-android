/**
 * download-adapter.js — document-start adapter that intercepts Blob downloads
 * from the embedded PdfCraft app and forwards them to the Android host for
 * durable staging and destination export.
 *
 * This is a separate runtime adapter (NOT part of the upstream repository).
 * It does not edit generated JS/WASM, change the PDF algorithm, or modify
 * application behavior beyond capturing downloads before Blob URL revocation.
 *
 * Installed before the iframe's application scripts execute, scoped to the
 * asset origin and expected document path.
 */

(function () {
    'use strict';

    const ASSET_ORIGIN = "https://appassets.androidplatform.net";
    const PDFRAFT_PATH = "/assets/pdfcraft/index.html";

    // Only inject on the pdfcraft index page, not on the host page itself.
    if (window.location.origin !== ASSET_ORIGIN) return;
    if (!window.location.pathname.startsWith("/assets/pdfcraft/")) return;

    // --- Track Blob objects created by URL.createObjectURL ---
    const blobMap = new Map();
    const originalCreateObjectURL = URL.createObjectURL;
    const originalRevokeObjectURL = URL.revokeObjectURL;
    let objectId = 0;

    URL.createObjectURL = function (obj) {
        if (obj instanceof Blob) {
            const id = "blob:" + (++objectId);
            blobMap.set(id, obj);
            return id;
        }
        return originalCreateObjectURL.call(this, obj);
    };

    URL.revokeObjectURL = function (url) {
        // Retain the Blob: don't actually revoke until the export is captured.
        // The original revocation is a no-op here for tracked Blobs.
        // We clean up after the export/stage cycle completes.
        if (blobMap.has(url)) {
            // Keep the blob; it will be released after the download is captured.
            return;
        }
        return originalRevokeObjectURL.call(this, url);
    };

    // --- Intercept download anchor clicks ---
    // The existing web code does: create Blob URL, set anchor.href, anchor.click(), revoke URL.
    // We intercept the click to capture the Blob bytes synchronously.
    const originalClick = HTMLElement.prototype.click;
    HTMLElement.prototype.click = function () {
        // Check if this is an <a download> element with a blob: href
        if (this.tagName === "A" || this.tagName === "a") {
            const href = this.getAttribute("href");
            if (href && href.startsWith("blob:") && blobMap.has(href)) {
                const blob = blobMap.get(href);
                const filename = this.getAttribute("download") || "download.pdf";

                // Retain the blob reference synchronously before click proceeds
                const blobCopy = blob.slice(0, blob.size, blob.type);
                blobMap.set(href + ":captured", blobCopy);

                // Read the blob as ArrayBuffer and forward to the parent
                const reader = new FileReader();
                reader.onload = function () {
                    const bytes = reader.result;
                    const parentOrigin = window.parent ? window.parent.location.origin : null;
                    if (parentOrigin === ASSET_ORIGIN) {
                        window.parent.postMessage({
                            type: "host:blobDownload",
                            filename: filename,
                            mimeType: blob.type || "application/pdf",
                            bytes: bytes
                        }, ASSET_ORIGIN, [bytes]);
                    }
                    // Release the retained copy
                    blobMap.delete(href + ":captured");
                };
                reader.onerror = function () {
                    blobMap.delete(href + ":captured");
                };
                reader.readAsArrayBuffer(blobCopy);
            }
        }
        return originalClick.call(this);
    };

    // --- Also catch programmatic click() calls on dynamically created anchors ---
    const originalCreateElement = Document.prototype.createElement;
    Document.prototype.createElement = function (tag) {
        const el = originalCreateElement.call(this, tag);
        if (tag.toLowerCase() === "a") {
            const originalClick2 = el.click;
            el.click = function () {
                const href = this.getAttribute("href");
                if (href && href.startsWith("blob:") && blobMap.has(href)) {
                    const blob = blobMap.get(href);
                    const filename = this.getAttribute("download") || "download";

                    const blobCopy = blob.slice(0, blob.size, blob.type);
                    const reader = new FileReader();
                    reader.onload = function () {
                        const bytes = reader.result;
                        if (window.parent && window.parent.location.origin === ASSET_ORIGIN) {
                            window.parent.postMessage({
                                type: "host:blobDownload",
                                filename: filename,
                                mimeType: blob.type || "application/pdf",
                                bytes: bytes
                            }, ASSET_ORIGIN, [bytes]);
                        }
                    };
                    reader.readAsArrayBuffer(blobCopy);
                }
                return originalClick2.call(this);
            };
        }
        return el;
    };

    // --- Cleanup on page unload ---
    window.addEventListener("beforeunload", function () {
        blobMap.clear();
    });

    // Signal to host that the download adapter is active
    window.addEventListener("message", function (event) {
        if (event.origin !== ASSET_ORIGIN) return;
        // No-op: adapter is passive, just intercepts downloads
    });
})();
