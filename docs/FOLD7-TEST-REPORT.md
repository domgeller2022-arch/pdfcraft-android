# PdfCraft on Android: Galaxy Z Fold7 Test Report

**Date:** 2026-10-15
**Target device:** Samsung Galaxy Z Fold7 (SM-F956U) — **not physically tested**
**Android:** 16 (API 36) target
**Baseline Commit Audited:** `c285ad4074d89eca247c69a46175417adaf348b6`

> Status legend: **PASS** = verified by automated test, static verification, or command;
> **PENDING** = requires physical Galaxy Z Fold7 device or Android instrumentation execution;
> **FAIL** = attempted and did not pass.

## 1. Build & Verification Summary

| Check | Status | Evidence |
|-------|--------|----------|
| Bundled web asset SRI (`sha384`) | **PASS** | `index.html` integrity hashes match `pdfcraft-web-662b253c51788020.js` and `_bg.wasm` |
| `verifyAndSyncWebAssets` Gradle gate | **PASS** | Pre-build task verifies `index.html`, `.js`, `.wasm`, and SHA-384 integrity before packaging |
| JS syntax & protocol harness (`node`) | **PASS** | `scripts/verify-host-protocol.cjs` verifies duplicate-chunk rejection (`seq=0` twice), 4-byte view over 6-byte buffer slicing, SHA-256 vs Node crypto, chunked exports, and `host:saveChunkAck` backpressure |
| `ExportQueueTest` (16 Robolectric tests) | **PASS** | Covers staging, sender SHA-256 verification, chunked disk streaming, duplicate/out-of-order/metadata/concurrency guards, orphan stage-file reconciliation, picker state persistence across recreation, and MIME persistence |
| Startup asset lookup on device | **PENDING** | Needs physical Fold7 launch to confirm |
| Open → edit → Save → reopen | **PENDING** | Device testing not yet performed |
| Fold/unfold & picker recreation continuity | **PENDING** | Device testing not yet performed |
| Split/export capture & Share | **PENDING** | Device testing not yet performed |

## 2. Post-`c285ad4` Audit & Supplement Remediations

| # | Area | Defect in `c285ad4` | Fix Applied |
|---|------|---------------------|-------------|
| 1 | Transfer protocol & flow control (`host.js`, `HostMessageBridge.kt`, `ExportQueue.kt`) | Missing duplicate/missing/out-of-range chunk checks, metadata consistency checks, sender checksums, timeouts, concurrency caps, and backpressure (sending chunk 0 twice triggered early import assembly and threw on `undefined`) | Added synchronous FIPS 180-4 SHA-256 sender checksums on imports and exports, strict sequential `seq == expectedSeq` & metadata validation, concurrency & 60s timeout bounds, and per-chunk `host:saveChunkAck` backpressure |
| 2 | Typed-array view slicing (`host.js`) | `toArrayBuffer` returned `input.buffer` without slicing `[byteOffset, byteOffset + byteLength]`, exporting a 6-byte backing buffer for a 4-byte view | Sliced non-zero offset or partial-length `ArrayBuffer.isView` inputs to their exact range |
| 3 | Phone-safe import limits (`DocumentIo.kt`) | `readBytes` grew `ByteArrayOutputStream` without a bound before the 2 GiB staging check | Added a 100 MiB phone-safe import cap (`MAX_IMPORT_BYTES`), advisory `OpenableColumns.SIZE` pre-check, streaming enforcement, and `OutOfMemoryError` guard |
| 4 | Crash-safe metadata & orphan recovery (`ExportQueue.kt`, `DocumentIo.kt`) | `.job` metadata used direct `writeText`; stage-file fallback wrote in place; crash between staging PDF and committing `.job` left an unrecoverable orphan PDF | Used NIO `Files.move(..., REPLACE_EXISTING, ATOMIC_MOVE)` after `fd.sync()` for all stage and metadata writes; added orphan stage-file reconciliation in `recoverPendingExports` |
| 5 | Picker state across Activity recreation (`MainActivity.kt`, `ExportQueue.kt`, `PdfCraftApplication.kt`) | `currentExportJob` and `pickerQueue` lived only in Activity memory; Activity recreation while `ACTION_CREATE_DOCUMENT` was open lost the job association; `ioExecutor` was tied to Activity | Persisted active picker job ID and queue in `ExportQueue` (`picker_state.prop`) + `onSaveInstanceState`; moved `ioExecutor` to `PdfCraftApplication` |
| 6 | Visible write/verify errors (`DocumentIo.kt`, `MainActivity.kt`) | Write exceptions only logged and posted `host:saveResult` to console; unreadable output providers were conflated with corrupt writes | Added 3-state `VerificationResult` (`VERIFIED`, `UNAVAILABLE`, `MISMATCH`) and visible recovery `AlertDialog`s (`Save / Share / Keep / Discard`) on all write/stage failures |
| 7 | In-app file picker (`PdfCraftChromeClient.kt`) | Raw file extensions (`.pdf`, `.png`, `.json`, `.xfdf`) were passed into `Intent.EXTRA_MIME_TYPES`; replacing `uploadCallback` leaked the previous callback | Normalized extensions and comma-separated lists into valid MIME types; completed any superseded `uploadCallback` with `null` |
| 8 | Bridge UI-thread delivery (`MainActivity.kt`, `HostMessageBridge.kt`) | `onAppReady` called `WebViewCompat.postWebMessage` on `ioExecutor` (caught and logged by `handleMessage`, losing the incoming intent import) | Wrapped `onAppReady` and `onDirtyChanged` in `runOnUiThread`; enforced main-Looper delivery in `HostMessageBridge.sendToHost` |
| 9 | Fold7 lifecycle & insets (`MainActivity.kt`) | `onPause()` called `webView.pauseTimers()`; `applyWindowInsets` added `systemBars + displayCutout`; `onRenderProcessGone` and startup timeout were absent | Removed `pauseTimers()` from `onPause()`, installed union `OnApplyWindowInsetsListener`, synced dark-mode status bar icons, and added `onRenderProcessGone` + 25s startup watchdog |
| 10 | Build scripts & docs (`scripts/`, `app/build.gradle.kts`, `docs/COMPATIBILITY.md`) | `scripts/build-web.sh` resolved `WORKSPACE` to `pdfcraft-android/`; scripts had hardcoded `/Users/dd/...` paths; `COMPATIBILITY.md` was missing | Fixed sibling path resolution, removed hardcoded paths, added `verifyAndSyncWebAssets` Gradle task, and authored `docs/COMPATIBILITY.md` |

## 3. Acceptance Gate (Pending Physical Galaxy Z Fold7 Verification)

Unit tests (`ExportQueueTest`) and Node.js protocol tests verify component logic on the JVM/V8, but do **not** replace physical device verification. Before release sign-off, verify on the physical Galaxy Z Fold7:
1. Cold startup in airplane mode on cover (6.5") and inner (8.0") displays.
2. Original in-app file chooser workflows (Open, Combine, Insert, image import).
3. Incoming Android `ACTION_VIEW` ("Open With") and `ACTION_SEND` ("Share") intents.
4. Save → destination picker → independent SHA-256 verification → reopen.
5. Non-PDF (`.csv`, `.png`, `.xfdf`, `.txt`) and multi-megabyte chunked Blob exports.
6. Picker cancellation, Activity recreation while Open/Save pickers are on screen, and process-death restart recovery.
7. Malformed transfer rejection and provider write-failure recovery dialogs.
8. Repeated fold/unfold, rotation, split-screen, and IME transitions with unsaved edits.
