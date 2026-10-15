# PDFCraft Android: consolidated Fold7 fix

This package supersedes the earlier touch-only and v2/v3/v4 patches. Apply ONE complete patch to Android commit `511793178132937757064e34cad09ab9a21fbc3a`. Do not layer it over the earlier replacement patches. The original upstream repository stays clean: the build creates a disposable copy and applies the included Rust patch there.

## What is included

| Issue | Implemented change |
|---|---|
| Tapping existing text does nothing after touch release | The Rust paragraph and image hit tests use `hover_pos().or(interact_pos())`, preserving the release position when eframe emits `PointerGone`. No synthetic mouse-event shim is required. |
| A full-page background image wins over editable text | Paragraph hit testing runs before image hit testing. Active image drags keep their ownership; tapping image-only space still selects images. |
| Cover-screen toolbar overlap and squeezed document | Below 780 logical points wide or 480 points high (keyboard/landscape), a compact toolbar, mode menu and command-search button replace the desktop bars. Tools and inspectors open one at a time. The document uses the remaining width. No 780-pixel shrink-to-fit hack. |
| Text formatting, swatches and editor overflow | Wrapped formatting controls, larger compact targets, bounded scrolling paragraph editor, explicit Done/Cancel controls. Cancel does not accidentally commit on focus loss. |
| Dialog buttons inaccessible on a narrow screen or with keyboard | Dialog bodies are bounded and scrollable; header/footer fields stack vertically. Footer actions remain outside the header/footer body scroll. |
| Fold/unfold damages desktop layout | The compact presentation bypasses the saved docking tree; it does not rewrite that tree. The wider display uses the existing desktop layout. |
| Side controls overflow at reduced height | Compact right-side controls and the floating page tools scroll vertically. |
| Chunk ACK bypass / stale ACKs / retry collisions | Keeps strict matching transfer ID and sequence ACK flow from 5117931. Retry uses a fresh ID. Delayed, absent and stale ACK checks are included. |
| Unbounded failed-export memory | Failed buffers have an explicit byte limit. If retention is unavailable, the UI says to keep the document open and save again instead of offering a false Retry action. |
| Back exits after a failed export / Later has no review route | Back now offers Review / Stay / explicit Leave. Tiny failure records remain even if their payload cannot fit in retry memory. Review reopens Retry/Discard or the non-retained failure explanation. |
| Staging and metadata sync errors swallowed | Required private-file sync failures propagate; incomplete transfers are cleaned up; durable staged PDFs remain recoverable after metadata failure. |
| Failed picker-state persistence hidden | Records and surfaces persistence errors, retaining the staged file. |
| Recovered picker offered twice | Removes the promoted active job from the waiting queue and filters invalid recovery entries. |
| Activity destroyed during export staging | Application-scoped completion delivery resolves the current Activity at delivery time and replays durable jobs when an Activity attaches. A stale Activity cannot detach the replacement listener. |
| Recreated Activity launches duplicate picker | Saves/restores the launched transfer ID and resumes deferred work once. |
| APK builds successfully with old WASM | The web build records upstream/font pins, Rust patch hash, lockfile hash and each asset hash. Android packaging checks the patch and asset hashes before building. |
| Missing notices and provenance | Packages source notices, asset/font/dependency licence texts, modification notice and machine-readable provenance. Restricted ArtCraft branding is not embedded in the modified interface. |

The PDF engine and document format are retained. Necessary touch/layout changes are in `patches/pdfcraft-fold7.patch`; the Android shell changes are in the complete outer patch. Version is `1.0.1-fold7`, version code 2.

## Apply and build from the complete patch

1. Keep the original working copy and any uncommitted work. Use a separate clone or a clean branch rooted at the exact Android commit above. Do not reset a working tree with uncommitted work.
2. Copy `PdfCraft-Fold7-Complete.patch` beside that clone. In the clone, verify the baseline and apply:

```sh
git rev-parse HEAD
git status --short
git apply --check ../PdfCraft-Fold7-Complete.patch
git apply ../PdfCraft-Fold7-Complete.patch
```

The first output must be `511793178132937757064e34cad09ab9a21fbc3a`; the second must be empty before applying. A failed apply check means the checkout is not the expected baseline. Do not force or partially apply the patch.

3. The complete patch includes rebuilt web assets and their provenance. To build Android from those bundled files, use JDK 17 and Android SDK 36:

```sh
export JAVA_HOME="/Users/dd/Library/Java/jdk-17.0.20.1+1/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
node scripts/verify-host-protocol.cjs
node scripts/verify-retry-protocol.cjs
./gradlew testDebugUnitTest lintDebug assembleDebug --no-daemon
```

Use the actual JDK location on another computer. The `Contents/Home` suffix matters for this macOS JDK. The debug APK is `app/build/outputs/apk/debug/app-debug.apk`.

4. Install the supplied debug APK or the APK just built. Its package is `com.domgeller.pdfcraft.debug`. A release installation can coexist because its package lacks `.debug`. An existing debug installation can update only if the signing keys match. Preserve exported documents and pending recovery copies before replacing an installation; do not uninstall just to bypass a signature mismatch without checking those files.

## Rebuild the web engine from source

This is only needed when changing the Rust patch or independently reproducing the web build. Do not hand-edit the disposable `build/patched-upstream` directory.

Required: Rust 1.95 or compatible newer toolchain, wasm32-unknown-unknown target, Trunk 0.21.14, Git and Python 3. The locked build uses eframe/egui 0.36.2. Trunk resolves the matching wasm-bindgen tool from Cargo.lock. Initial dependency/tool installation may need network access.

Provide these clean source checkouts. If an existing checkout contains edits, keep it untouched and make a fresh clone; do not reset it:

- PDFCraft: `bbbe60015c571ebfc39708ee07777b89e267122a`.
- craft-fonts: `8dcdacd5153e64560d109541a47d806f26f048c0`.

```sh
rustup target add wasm32-unknown-unknown
git clone https://github.com/domgeller2022-arch/pdfcraft.git ../pdfcraft-upstream-fold7-clean
git -C ../pdfcraft-upstream-fold7-clean checkout --detach bbbe60015c571ebfc39708ee07777b89e267122a
git clone https://github.com/storytold/craft-fonts.git ../craft-fonts-fold7-clean
git -C ../craft-fonts-fold7-clean checkout --detach 8dcdacd5153e64560d109541a47d806f26f048c0
export PDFCRAFT_UPSTREAM_DIR="$(cd ../pdfcraft-upstream-fold7-clean && pwd)"
export CRAFT_FONTS_DIR="$(cd ../craft-fonts-fold7-clean && pwd)"
./scripts/build-web.sh
./gradlew testDebugUnitTest lintDebug assembleDebug --no-daemon
```

`build-web.sh` validates source pins and cleanliness, creates the disposable copy, applies the phone patch, compiles the actual WASM app, replaces stale hashed assets, packages notices and writes provenance. It then verifies that the original upstream checkout is still unchanged. Do not bypass `verifyAndSyncWebAssets` when producing an APK.

To run the new Rust UI regression tests against the patched build copy:

```sh
cargo test --manifest-path build/patched-upstream/Cargo.toml -p pdfcraft-ui-egui --test editing mobile_ --locked
```

Optional `PDFCRAFT_PHONE_SCREENSHOTS=/absolute/output/directory` makes the compact-layout test save rendered screenshots on a machine with a supported graphics adapter.

## Physical Galaxy Z Fold7 acceptance checks

These must be performed on the installed new APK; a desktop screenshot is not a Samsung keyboard or WebView test.

1. Open the same PDF that edits correctly in the desktop app. Select Edit → Edit text & images. Tap an existing paragraph. The paragraph editor should open and Samsung Keyboard should appear. Replace text, use Done, save to a new PDF, reopen it in another reader and confirm the actual content changed.
2. Repeat with a paragraph over a full-page background image. Text should win there; tapping image-only space should still select the image. Test moving and resizing an image.
3. Edit text and press Cancel. The draft must not be committed. Repeat with Back, switching tools, multiline text, punctuation and the languages you use. Non-Latin PDF text rewriting remains subject to the upstream engine's existing font support.
4. On the cover screen, verify Open, Save, mode menu and command search. Open tools/formatting, change alignment and colour, then close the panel. Open Header and Footer; reach all six fields and Cancel/OK with and without the keyboard. Test landscape and split-screen as well as portrait.
5. Open the same document on the inner screen. Fold/unfold several times with an unsaved edit, an open dialog and the keyboard visible. The document must stay open and controls remain reachable.
6. Export several files, cancel a destination picker, retry a retained export, background/return during staging and test recovery after force-stop once a staged export exists. Reopen every saved PDF. A process killed before staging is durable cannot be promised recovery.
7. Test a storage provider that permits readback and one that does not. The app must distinguish verified output from an accepted write whose checksum cannot be independently checked. Keep the staged backup where appropriate.

## Scope and release

This is one implementation of all identified issues, with test evidence supplied separately. It is not a claim that every PDFCraft feature or every Android device has been certified. The user's specific PDF was not provided, so it must still be used in the physical acceptance test. A scanned page with no text objects remains image content; this patch does not add OCR.

The supplied APK is a debug build for testing. For a distributable release, use the existing signing configuration/keystore locally and run `assembleRelease`; do not share signing credentials. Record the physical test results before labelling the release Fold7-verified. Do not apply the older touch adapter or viewport scaling patches on top of this package.

## Build time and APK size

This testing build retains Rust release compilation and skips the additional Binaryen size-optimization pass (`data-wasm-opt="0"`). That avoids a long packaging delay and may produce a larger APK. See [Trunk asset configuration](https://github.com/trunk-rs/trunk/blob/main/guide/src/assets/index.md). All touch, layout and export fixes remain included.
