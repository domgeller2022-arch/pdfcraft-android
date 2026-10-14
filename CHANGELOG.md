# Changelog

All notable changes to the PdfCraft Android wrapper are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased] — Round 3 & 4 comprehensive audit remediation (2026-10-15)

Remediation of post-`c285ad4` audit findings and external auditor supplement.

### Fixed — transfer protocol validation, checksums, & backpressure
- **`host.js` / `HostMessageBridge.kt` / `ExportQueue.kt`**: compute and verify independent
  FIPS 180-4 SHA-256 sender checksums across both single-shot (`host:save`, `host:blobDownload`,
  `host:import`) and chunked (`host:saveChunk` + `host:saveComplete`, `host:importChunk`)
  transfers.
- **`host.js`**: enforce strict sequential ordering (`seq === expectedSeq`), duplicate-chunk
  rejection, metadata consistency checks, exact byte-length verification, concurrency limits
  (`MAX_CONCURRENT_IMPORTS = 4`), and 60s transfer timeouts in `handleImportChunk` (fixing the
  reproduced bug where sending chunk 0 twice triggered early assembly and threw on `undefined`).
- **`host.js` / `HostMessageBridge.kt`**: implement per-chunk `host:saveChunkAck` backpressure
  and a bounded outbound export queue so large saves stream one 256 KiB chunk at a time to
  disk rather than flooding WebView IPC or buffering whole files in heap memory.
- **`host.js`**: slice `ArrayBuffer.isView` payloads to `[byteOffset, byteOffset + byteLength]`
  so sub-view exports (such as a 4-byte view over a 6-byte backing `ArrayBuffer`) export only
  the view's exact range.

### Fixed — crash-safe metadata, orphan reconciliation, & picker recreation state
- **`DocumentIo.kt` / `ExportQueue.kt`**: replace non-atomic fallback writes with NIO
  `Files.move(..., REPLACE_EXISTING, ATOMIC_MOVE)` after `fd.sync()` for both private stage
  files and `.job` metadata files.
- **`ExportQueue.kt`**: add orphan stage-file reconciliation during `recoverPendingExports` so
  a crash between syncing the PDF stage file and committing its `.job` metadata (or a truncated
  `.job` file) still recovers the staged PDF on next launch.
- **`ExportQueue.kt` / `MainActivity.kt` / `PdfCraftApplication.kt`**: persist the active
  destination-picker job ID and queued picker IDs across Activity recreation and process death
  (via `savedInstanceState` and `picker_state.prop`), and move `ioExecutor` to
  `PdfCraftApplication` so Activity recreation never orphans an in-flight picker result or
  aborts a background disk copy.

### Fixed — phone-safe import limits & 3-state destination verification
- **`DocumentIo.kt`**: enforce a 100 MiB phone-safe import limit (`MAX_IMPORT_BYTES`) during
  dynamic stream reading (with an early advisory check against `OpenableColumns.SIZE` and an
  `OutOfMemoryError` guard).
- **`DocumentIo.kt` / `MainActivity.kt`**: introduce 3-state `VerificationResult`
  (`VERIFIED`, `UNAVAILABLE`, `MISMATCH`) so write-only or cloud SAF providers that accept the
  write but disallow read-back are distinguished from actual checksum mismatches, and surface
  visible recovery dialogs (`Save to device… / Share via Android… / Keep pending / Discard`)
  on all save or staging errors.

### Fixed — file chooser, MIME handling, bridge threading, & Fold7 lifecycle
- **`PdfCraftChromeClient.kt`**: normalize upstream `rfd` file-extension filters (`.pdf`,
  `.png`, `.jpg`, `.json`, `.xfdf`, `.txt`, `.svg`, and comma-separated lists) into valid
  Android MIME types for `Intent.EXTRA_MIME_TYPES`, and complete any superseded
  `uploadCallback` with `null` before replacement.
- **`MainActivity.kt` / `HostMessageBridge.kt`**: wrap `onAppReady` and `onDirtyChanged` in
  `runOnUiThread` (fixing the caught background-thread `WebViewCompat.postWebMessage`
  exception that caused incoming intent imports to fail and be logged as errors), enforce
  main-Looper delivery in `sendToHost`, and validate `sourceOrigin == assetOrigin`.
- **`host.js` / `host.html` / `download-adapter.js`**: defer iframe `src` assignment until
  after message listeners are registered; accept Chromium WebView's `event.origin === ""` on
  native `postWebMessage` string deliveries; revoke non-download Blob URLs after a grace
  window; and suppress default `blob:` navigation on captured anchor clicks.
- **`MainActivity.kt` / `AndroidManifest.xml` / `res/xml/file_paths.xml`**: remove
  `webView.pauseTimers()` from `onPause()`, implement `onRenderProcessGone` and a 25s startup
  watchdog, add an unexported `FileProvider` for outbound Share of staged exports, and use
  `ViewCompat.setOnApplyWindowInsetsListener` with the union of
  `systemBars() or displayCutout() or ime()`.
- **`app/build.gradle.kts` / `scripts/` / `docs/COMPATIBILITY.md`**: add `verifyAndSyncWebAssets`
  `preBuild` task, optional release keystore signing, portable script paths, and
  `docs/COMPATIBILITY.md`.

## [Unreleased] — Round 1 & 2 audit remediation (`c285ad4`, 2026-10-15)

Remediation of initial findings from the audit of commit `858c43c`:
- **`HostMessageBridge.kt`**: call `WebViewCompat.addDocumentStartJavaScript(...)` and register
  listener as `"WebMessageListener"` with `setOf(assetOrigin)`.
- **`gradle/libs.versions.toml`**: pin Robolectric `4.13` (note: `gradle-wrapper.jar` was
  already tracked in `858c43c` and `c285ad4`).
- **`LocalContentServer.kt` / `MainActivity.kt`**: register `WebViewAssetLoader` under
  `/assets/` and surface main-frame load errors.
- **`host.js` / `download-adapter.js`**: parse JSON strings from `postWebMessage`, move
  `host:blobDownload` into the iframe branch, add idempotency guard, and preserve real Blob URLs.
- **`DocumentIo.kt`**: read document bytes dynamically instead of trusting `OpenableColumns.SIZE`.
- **`MainActivity.kt` / `values-v27/themes.xml`**: migrate to `OnBackPressedDispatcher` and
  move `android:windowLayoutInDisplayCutoutMode` to `values-v27`.

## [0.1.0] — initial commit (`858c43c`, 2026-10-14)

Initial Android wrapper hosting the bundled PdfCraft WebAssembly web app in an Android
`WebView` via `WebViewAssetLoader`.
