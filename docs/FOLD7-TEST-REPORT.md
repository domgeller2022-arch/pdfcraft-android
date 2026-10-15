# Consolidated Fold7 verification report

Prepared 2026-10-16. This report supersedes test-status claims in the earlier replacement guides. The canonical application instructions are in `FOLD7-COMPLETE-FIX.md`.

## Baseline and source scope

- Android baseline: `511793178132937757064e34cad09ab9a21fbc3a`.
- PDFCraft source: `bbbe60015c571ebfc39708ee07777b89e267122a` plus `patches/pdfcraft-fold7.patch`.
- craft-fonts input: `8dcdacd5153e64560d109541a47d806f26f048c0`.
- This implementation did not edit the original source repositories. Unrelated documentation edits appeared in the original upstream checkout during verification and were preserved. The build uses a separate clean pinned checkout; the phone changes live in the supplied patch and isolated build copy.
- Android version: `1.0.1-fold7` (code 2); debug application ID: `com.domgeller.pdfcraft.debug`.

## Automated checks

| Check | Result |
|---|---|
| Android unit and lifecycle regressions | PASS: 25 tests, 0 failures, 0 skipped |
| Host protocol harness | PASS: 10 checks |
| Retry protocol harness | PASS: fresh transfer IDs, stale ACK rejection, pending-work notification |
| Rust WASM source check | Preliminary release-target check passed; final-source compilation is covered by the optimized build below |
| Final optimized web build | PASS: release WASM with locked inputs and final phone patch |
| Native touch/layout regressions | PASS: 4/4 on final source, including draft retention through panel open/close |
| Rotate/extract dialog regression | PASS on final source |
| Existing editing suite | 83/84 passed before the final dialog correction; the failing rotate/extract case then passed individually. The full suite was not repeated after that correction. |
| Android lint | PASS: 0 errors, 36 warnings (24 unused-resource warnings, 3 dependency-version notices and 9 API/configuration/UI suggestions; see XML report) |
| APK build with provenance guard enabled | PASS: assembleDebug, guard enabled |
| Complete patch applies to baseline | PASS: clean Android 5117931 checkout |
| APK asset/provenance comparison | PASS: exact web asset bytes, provenance and packaged notices; debug signature verified |

Android regressions include required data/metadata sync failures, orphan recovery, restored picker fallback, stale Activity-owner teardown, a real simulated Activity destroy/recreate during delayed export completion, and explicit review/leave handling for host-only failed exports. Robolectric exercises Android lifecycle logic; it does not simulate Samsung WebView rendering or a physical keyboard.

The protocol checks cover delayed/missing ACKs, ten multi-chunk exports, overflow retention, failed-buffer memory limits, metadata-only failure review/discard and stale retry messages. They use an isolated JavaScript harness; they do not replace Android document-provider tests.

No additional broad test, Clippy or graphics-rendering runs were started after the request to finish promptly. The passing mobile tests inspect real egui interaction and geometry headlessly; no simulated phone image is presented as physical-device proof.

## Required device acceptance

**Physical Galaxy Z Fold7 testing has not been performed by this audit.** The user's specific PDF was not supplied. Run the checklist in `FOLD7-COMPLETE-FIX.md` on the installed new APK, including existing paragraph edits over background images, Done/Cancel, formatting, the cover-screen keyboard, inner/outer transitions, save/reopen and recovery.

Also verify cold startup in airplane mode, Android Open With/Share imports, non-PDF exports, picker cancellation, split-screen, and both readable and write-only document providers. The APK is debug-signed for testing. Release signing and physical-device acceptance remain separate release steps; no desktop image or passing build is described here as physical-phone proof.
