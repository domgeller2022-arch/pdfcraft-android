# Changelog

All notable changes to the PdfCraft Android wrapper are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased] — audit remediation (2026-10-15)

Remediation of findings from an external audit of commit `858c43c`. Verified on a debug
build with `JAVA_HOME='/Users/dd/Library/Java/jdk-17.0.20.1+1/Contents/Home'`.

### Fixed — build blockers
- **`HostMessageBridge.kt`**: call `WebViewCompat.addDocumentStartJavaScript(...)`. The
  pinned AndroidX WebKit 1.12.0 API does not expose `addDocumentStartScript`.
- **`gradle/libs.versions.toml`**: pin Robolectric to the published `4.13` (`4.13.2` is
  not published in the configured repositories).

### Fixed — startup / asset loading
- **`LocalContentServer.kt`**: register the `WebViewAssetLoader` under `/assets/` instead
  of `/shell/`, `/pdfcraft/`, and `/`. The old mapping made `/assets/shell/host.html`
  resolve to the nonexistent APK path `assets/assets/shell/host.html`, so the app could
  not start.
- **`LocalContentServer.kt`**: validate requested paths, reject `.`/`..` traversal, and
  return a local 404 instead of falling through to network access.
- **`MainActivity.kt`**: route `shouldInterceptRequest` through
  `localContentServer.interceptRequest(...)` and surface main-frame load errors.

### Fixed — native ↔ web bridge
- **`MainActivity.kt` / `HostMessageBridge.kt`**: register the message listener as
  `WebMessageListener` instead of `"/"`, so `window.WebMessageListener` exists in the page.
- **`HostMessageBridge.kt`**: use `setOf(assetOrigin)` as the permitted-origin set instead
  of `emptySet()`.
- **`host.js`**: parse JSON string payloads delivered by `WebViewCompat.postWebMessage`.
- **`host.js`**: generate save transfer IDs with a monotonic counter suffix so
  same-millisecond saves cannot collide.
- **`host.js`**: make buffer type detection realm-agnostic (`Object.prototype.toString` +
  `ArrayBuffer.isView`); `instanceof` is unreliable for messages crossing the
  iframe↔parent boundary.

### Fixed — export / blob downloads
- **`host.js`**: handle `host:blobDownload` in the validated iframe branch. It was
  previously in the native-message branch, which is unreachable because the iframe branch
  returns first.
- **`download-adapter.js`**: add an idempotent installation guard so the document-start
  injection and the iframe-load fallback cannot stack hooks and emit duplicate exports.
- **`download-adapter.js`**: return the real `URL.createObjectURL` result instead of a
  fabricated `blob:N` URL, and drop the duplicate `Document.createElement` click
  interception.
- **`host.html`**: stop loading the adapter on the host page (it self-exits there); it is
  now injected into the iframe.

### Fixed — document I/O
- **`DocumentIo.kt`**: read document bytes dynamically. The previous implementation trusted
  the provider-reported `OpenableColumns.SIZE` and could truncate or zero-pad the result.

### Fixed — Android 16 / lint
- **`MainActivity.kt`**: replace the deprecated `onBackPressed()` / `KeyEvent.KEYCODE_BACK`
  handling with an AndroidX `OnBackPressedDispatcher` callback (required for API 36
  predictive back).
- **`themes.xml` / `values-v27/themes.xml`**: move `android:windowLayoutInDisplayCutoutMode`
  into `values-v27` (it requires API 27; `minSdk` is 26).

### Fixed — export recovery
- **`PdfCraftApplication.kt`**: expose `getRecoverableJobs()` / `clearRecoveredJob()` for
  the recovery UI.
- **`MainActivity.kt`**: track jobs staged this session (`pendingExports`), merge them with
  process-death recovery, delete the staged file and `.job` metadata after a verified save,
  and serialize destination pickers so only one is in flight at a time.

### Added
- `app/src/test/java/com/domgeller/pdfcraft/ExportQueueTest.kt` — 7 Robolectric unit tests
  covering staging, hashing, cancellation, and recovery.
- `docs/FOLD7-TEST-REPORT.md` — build/test/lint evidence with explicit
  PASS / PENDING / FAIL status.
- `app/src/main/res/values-v27/themes.xml` — API 27+ theme overrides.
- `CHANGELOG.md` — this file.

### Changed
- **`ExportQueue.kt`**: extract `validateStagingSize(Long)` so the size guard is testable
  without allocating a multi-gigabyte array.
- **`app/build.gradle.kts`**: use the version catalog for test dependencies, add Robolectric,
  and add a lint block.

### Verified (debug build)
- `assembleDebug` — PASS (APK ≈ 24.8 MB)
- `testDebugUnitTest` — PASS (7/7, 0 failures, 0 errors)
- `lintDebug` — PASS (0 errors, 45 warnings)

### Pending
- Physical Samsung Galaxy Z Fold7 testing: start-up, open → edit → save → reopen,
  cancellation/restart recovery, split/export, and repeated fold/unfold.
- Release signing configuration (debug build only so far).

## [0.1.0] — initial commit (2026-10-14)

Initial Android wrapper: a Kotlin shell hosting the bundled PdfCraft WebAssembly web app in
an Android `WebView`, served from `https://appassets.androidplatform.net` via
`WebViewAssetLoader`, with a `WebMessageListener` bridge, Storage Access Framework file
pickers, and an export staging queue.
