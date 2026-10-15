# PdfCraft Android — consolidated Fold7 build

Start with [the complete implementation and build guide](docs/FOLD7-COMPLETE-FIX.md). This version supersedes the earlier touch-only and replacement patches.

It builds on Android commit `511793178132937757064e34cad09ab9a21fbc3a`. The included `patches/pdfcraft-fold7.patch` adds native Rust touch and compact-layout fixes to an isolated copy of upstream `bbbe60015c571ebfc39708ee07777b89e267122a`. The original upstream checkout is never edited.

See [verification](docs/FOLD7-TEST-REPORT.md) for checks actually run, and [compatibility](docs/COMPATIBILITY.md) for platform limitations. Device acceptance and release signing are separate from a successful debug build.
