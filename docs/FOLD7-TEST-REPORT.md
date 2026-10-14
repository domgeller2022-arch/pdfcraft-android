# PdfCraft on Android: Galaxy Z Fold7 Test Report

**Date:** 2026-10-15
**Target device:** Samsung Galaxy Z Fold7 (SM-F956U) — **not physically tested**
**Android:** 16 (API 36) target
**APK:** `app/build/outputs/apk/debug/app-debug.apk` (debug build, 24,750,661 bytes)

> Status legend: **PASS** = verified by a command in this document;
> **PENDING** = not yet verified (needs a device or a further command);
> **FAIL** = attempted and did not pass.

## 1. Build verification (verified locally)

Build command (JDK home **must** include `Contents/Home` on this macOS install):

```bash
JAVA_HOME='/Users/dd/Library/Java/jdk-17.0.20.1+1/Contents/Home' ./gradlew assembleDebug testDebugUnitTest --no-daemon
```

| Check | Status | Evidence |
|-------|--------|----------|
| `assembleDebug` | **PASS** | `BUILD SUCCESSFUL`; APK 24,750,661 bytes |
| `testDebugUnitTest` | **PASS** | `tests="7" skipped="0" failures="0" errors="0"` |
| `lintDebug` | **PASS (0 errors)** | `BUILD SUCCESSFUL`; 45 warnings, 0 errors |
| JS syntax (`node --check`) | **PASS** | `host.js`, `download-adapter.js` both parse |
| Startup asset lookup on device | **PENDING** | Fix applied; needs Fold7 launch to confirm |
| Open → edit → Save → reopen | **PENDING** | Device testing not performed |
| Fold/unfold continuity | **PENDING** | Device testing not performed |
| Split/export capture | **PENDING** | Device testing not performed |

## 2. Corrections applied in this round

| Area | Change | File |
|------|--------|------|
| Build blocker | `addDocumentStartScript` → `addDocumentStartJavaScript` (API name) | `HostMessageBridge.kt` |
| Build blocker | Robolectric `4.13.2` → published `4.13` | `gradle/libs.versions.toml` |
| Startup bug | Register `WebViewAssetLoader` under `/assets/` (not `/shell/`, `/pdfcraft/`, `/`) so `/assets/shell/host.html` resolves to APK asset `shell/host.html` | `LocalContentServer.kt` |
| Wired loader | Use `localContentServer.interceptRequest(...)` + main-frame error handlers | `MainActivity.kt` |
| Export path | Moved `host:blobDownload` handling into the **iframe** branch of the message listener (it was unreachable in the native-message branch after the iframe branch's early `return`) | `host.js` |
| Double injection | Idempotent installation guard so document-start + iframe-load routes cannot stack hooks | `download-adapter.js` |
| Tests | Added `@RunWith(RobolectricTestRunner::class)`; replaced the 2 GiB allocation test with a Long-based size-guard test | `ExportQueueTest.kt` |
| Size guard | Extracted `ExportQueue.validateStagingSize(Long)` so the limit is testable without allocating | `ExportQueue.kt` |
| Recovery | Session-tracked `pendingExports` merged with startup recovery; staged file + metadata removed after verified save; destination pickers serialized | `MainActivity.kt` |

## 3. Bridge and export defects — status

| # | Defect | Status | Fix |
|---|--------|--------|-----|
| 1 | Wrong JS object name (`"/"` vs `WebMessageListener`) | **PASS** | `bridgeObjectName = "WebMessageListener"` |
| 2 | Empty permitted-origin set rejected all messages | **PASS** | `setOf(assetOrigin)` |
| 3 | JSON string messages from `postWebMessage` ignored | **PASS** | `JSON.parse()` in host listener |
| 4 | `host:blobDownload` unreachable | **PASS** | Handler moved to validated iframe branch; Kotlin handler present |
| 5 | Same-millisecond save ID collision | **PASS** | Monotonic counter suffix |
| 6 | Adapter double-injection → duplicate exports | **PASS** | `window.__pdfCraftDownloadAdapterInstalled` guard |
| 7 | Fake `blob:1` URL replacing real Blob URL | **PASS** | Adapter returns original `createObjectURL` result |
| 8 | Provider-reported size truncation/padding on read | **PASS** | `readBytes()` reads dynamically |

> The earlier `bridge-regressions.cjs` was a **bug-demonstration** harness for the original code —
> it is not a passing test suite. Regression 4–6 were re-verified by source inspection and
> `node --check` only; runtime behavior still needs a device pass.

## 4. Known limitations (not fixed this round)

| Limitation | Notes |
|------------|-------|
| Staging limit is 2 GiB | Guard is now testable; a phone-appropriate bound (e.g. a fraction of free space) is still a product decision |
| Release signing | Debug build only; a release keystore must be supplied before distribution |
| `RequiresFeature` lint warning | Runtime feature check precedes the call; lint cannot prove it statically |
| Incremental single-chunk vs append paths | Chunked saves are buffered in memory in `HostMessageBridge`; very large exports hold the full buffer |
| Physical-device evidence | None yet — all startup/editing/export/fold claims are **PENDING** |

## 5. Recovery flow (as implemented)

1. Process death → `PdfCraftApplication.onCreate()` recovers jobs from the metadata dir.
2. New/cancelled exports this session → tracked in `MainActivity.pendingExports`.
3. Back press → merges both sources; shows a pending-export list.
4. Selecting a job → serialized destination picker (`requestSaveDestination`).
5. Verified save or explicit discard → staged file + `.job` metadata deleted (`removeStagedExport`).

## 6. Test artifacts

- Unit tests: `app/src/test/java/com/domgeller/pdfcraft/ExportQueueTest.kt` (7 tests)
- Test results: `app/build/test-results/testDebugUnitTest/TEST-com.domgeller.pdfcraft.ExportQueueTest.xml`
- Lint report: `app/build/reports/lint-results-debug.html` (0 errors, 45 warnings)
- Build log: `assembleDebug` produced `app/build/outputs/apk/debug/app-debug.apk`
