# PdfCraft on Android: Platform Compatibility Matrix

**Pinned Upstream Revision:** `bbbe60015c571ebfc39708ee07777b89e267122a` (`v0.6.0` workspace)
**Pinned Font Input (`craft-fonts`):** `8dcdacd5153e64560d109541a47d806f26f048c0`
**Target Device Baseline:** Samsung Galaxy Z Fold7 (Android 16 / One UI 8, API 36)

This document records the exact behavioral and architectural differences between the native desktop build of PdfCraft, its WebAssembly (`pdfcraft-web`) target, and the Android wrapper (`pdfcraft-android`), under the strict zero-upstream-change requirement.

## 1. Feature Support Summary

| Feature / Workflow | Desktop Target | Web (`pdfcraft-web`) & Android Wrapper | Notes & Limitations |
| :--- | :--- | :--- | :--- |
| **Viewing, Zoom, Pan, Bookmarks, Layers, Attachments** | Supported | **Supported** | Uses `eframe::Renderer::Glow` (WebGL2) inside the hardware-accelerated Android `WebView`. |
| **Page Operations (Merge, Split, Rotate, Reorder, Extract, Delete)** | Supported | **Supported** | Split/Extract outputs emit Blob downloads captured by `download-adapter.js` and routed through `ExportQueue`. |
| **Annotations, Comments, Highlights, Shapes, Freehand Ink** | Supported | **Supported** | Finger touch input works on both Galaxy Z Fold7 displays. Note: Galaxy Z Fold7 hardware does not support S Pen. |
| **Form Filling & Finger-Drawn Fill & Sign** | Supported | **Supported** | Standard interactive PDF forms and drawn signatures (`signature.png`) are preserved. |
| **Password Protection & Encryption** | Supported | **Supported** | Handled inside the original Rust PDF engine in WebAssembly. |
| **In-App File Pickers (Open, Combine, Insert, Image Import)** | Native OS dialog | **Supported via SAF** | Routed through `PdfCraftChromeClient.onShowFileChooser` with file-extension (`.pdf`, `.png`, `.jpg`, `.txt`, `.json`, `.xfdf`) to MIME normalization. |
| **Incoming "Open With" & "Share" (`ACTION_VIEW`, `ACTION_SEND`)** | OS file associations | **Supported** | Queued until `pdfcraft:ready` and delivered via `pdfcraft:open` without restarting existing tabs. |
| **Save & Export Delivery** | Direct filesystem write | **Staged SAF Save + Share** | Every Save stages bytes durably in private storage (`files/staging/`), verifies SHA-256, and prompts via `ACTION_CREATE_DOCUMENT` (Save As) or `FileProvider` Share. |
| **Pending Export Recovery** | N/A | **Supported** | Cancelled or interrupted exports persist across process death in `files/metadata/*.job` and prompt for Save / Share / Discard on launch and exit. |
| **Unsaved Session / Crash Recovery** | Desktop auto-recovery | **Limited to Staged Exports** | Upstream `pdfcraft-web` does not expose a snapshot or state-serialization postMessage API. If Android kills the process before the user taps Save, unsaved in-memory canvas edits and undo history cannot be restored. |
| **Save Completion Acknowledgement** | Synchronous disk write | **Asynchronous Wrapper Staging** | Upstream `pdfcraft-web` clears its dirty flag as soon as `pdfcraft:save` is posted to the parent window. The Android wrapper tracks pending staged exports independently until the user's destination write is verified. |
| **OCR (`.rten` Text Recognition Models)** | Filesystem model loading | **Unsupported in WASM** | `crates/ocr/src/lib.rs` loads `.rten` models via native filesystem paths rather than browser `fetch`. Supporting OCR on Android would require modifying upstream Rust code. |
| **Certificate / Digital-ID Signing (`.p12` / `.pfx` OS Store)** | Desktop certificate store | **WASM Exclusions Apply** | `crates/ui-egui/src/sign_ui.rs` excludes desktop certificate-store browsing on `wasm32`. Visual Fill & Sign works normally. |
| **Direct OS Printer Spooling** | Native OS print spooler | **Export-First ("Save as PDF")** | The WASM target has no direct OS print spooler; its Print action produces a print-ready PDF download captured by the Android export queue, which can then be printed or shared via Android's Share sheet. |
| **Embedded Fonts** | Full desktop font pack | **WASM Subset (`craft-fonts`)** | Preserves the upstream WASM font subset (`Noto Sans CJK SC`, `Noto Sans Arabic`, standard base fonts) to keep the WASM binary size manageable (~41 MB). |

## 2. Galaxy Z Fold7 Display & Continuity Notes

1. **Both Displays Supported**: The `WebView` fills the usable window on both the 6.5-inch cover display and the 8.0-inch inner display using adaptive window sizing (`resizeableActivity="true"`).
2. **Fold / Unfold Continuity**: `MainActivity` handles `orientation|screenSize|smallestScreenSize|screenLayout|keyboardHidden|uiMode|density` in-place so folding, unfolding, rotating, or entering split-screen resizes the `eframe` canvas without reloading the page or losing open tabs.
3. **Cover Screen Continuation Setting**: On One UI 8, folding the phone closed locks the screen by default unless PdfCraft is enabled under **Settings → Display → Continue apps on cover screen**.
4. **UI Scale Adjustment**: Because PdfCraft renders its toolbar and dialogs inside a Rust/egui WebGL canvas, users can adjust control sizing on the cover screen using PdfCraft's built-in **Interface size** preference (persisted in `localStorage` under `https://appassets.androidplatform.net`).
