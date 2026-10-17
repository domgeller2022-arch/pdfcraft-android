#!/usr/bin/env python3
"""Build the pinned upstream plus the reviewed phone patch in a disposable copy."""
import hashlib
import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
PIN = "bbbe60015c571ebfc39708ee07777b89e267122a"
FONTS_PIN = "8dcdacd5153e64560d109541a47d806f26f048c0"
UPSTREAM = Path(os.environ.get("PDFCRAFT_UPSTREAM_DIR", ROOT.parent / "pdfcraft-upstream")).resolve()
FONTS = Path(os.environ.get("CRAFT_FONTS_DIR", ROOT.parent / "craft-fonts")).resolve()
PATCH = ROOT / "patches/pdfcraft-fold7.patch"
SOURCE = ROOT / "build/patched-upstream"
WEB = ROOT / "build/web"
ASSETS = ROOT / "app/src/main/assets"
# Scan & OCR models built into the WASM (the web build has no file system to find them in).
# SHA-256 as pinned in upstream ATTRIBUTION.toml; fetch them with `cargo xtask models DIR` or
# from the URLs there. PDFCRAFT_OCR_MODELS_DIR=none builds without OCR.
OCR_MODELS = {
    "text-detection.rten": "f15cfb56bd02c4bf478a20343986504a1f01e1665c2b3a0ad66340f054b1b5ca",
    "text-recognition.rten": "e484866d4cce403175bd8d00b128feb08ab42e208de30e42cd9889d8f1735a6e",
}
OCR_MODELS_ATTRIBUTION = (
    "Scan & OCR models built into this app (pdfcraft-web WASM):\n"
    "  text-detection.rten, text-recognition.rten\n"
    "  Ocrs models by Robert Knight (ocrs-models; trained on HierText)\n"
    "  https://github.com/robertknight/ocrs-models  https://huggingface.co/robertknight/ocrs\n"
    "  Licence: CC-BY-SA-4.0 https://creativecommons.org/licenses/by-sa/4.0/legalcode\n"
    "  Unmodified.\n"
)

def ocr_models_dir():
    """The verified OCR model folder, or None when OCR is deliberately left out."""
    value = os.environ.get("PDFCRAFT_OCR_MODELS_DIR", "")
    if value == "none":
        return None
    if not value:
        raise SystemExit("Set PDFCRAFT_OCR_MODELS_DIR to the folder holding the OCR models (or to 'none' to build without OCR)")
    folder = Path(value).resolve()
    for name, expected in OCR_MODELS.items():
        path = folder / name
        if not path.is_file():
            raise SystemExit("Missing OCR model " + str(path))
        if sha(path) != expected:
            raise SystemExit("OCR model " + name + " does not match its pinned SHA-256; download it again")
    return folder

def run(*args, **kwargs):
    return subprocess.run([str(a) for a in args], check=True, **kwargs)

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def package_notices():
    notices = ASSETS / "notices"
    if notices.exists(): shutil.rmtree(notices)
    notices.mkdir(parents=True, exist_ok=True)
    def copy_notices(directory, prefix):
        if not directory.exists(): return
        for path in directory.rglob("*"):
            lower = path.name.lower()
            if path.is_file() and ("license" in lower or "licence" in lower or lower.startswith(("ofl", "notice", "copyright", "copying", "authors"))):
                target = notices / prefix / path.relative_to(directory)
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(path, target)
    for name in ["NOTICE", "ATTRIBUTION.toml", "ATTRIBUTION.md", "LICENSE-MIT", "LICENSE-APACHE"]:
        path = SOURCE / name
        if path.is_file(): shutil.copy2(path, notices / name)
    for directory, prefix in [(SOURCE / "assets", "assets"), (SOURCE / "vendor", "vendor"), (FONTS, "fonts")]:
        copy_notices(directory, prefix)
    shutil.copy2(FONTS / "ATTRIBUTION.md", notices / "fonts/ATTRIBUTION.md")
    # Include licence texts for registry dependencies, including embedded egui fonts.
    cargo_home = Path(os.environ.get("CARGO_HOME", Path.home() / ".cargo"))
    lock_text = (SOURCE / "Cargo.lock").read_text()
    packages = re.findall(r'name = "([^"\n]+)"\nversion = "([^"\n]+)"', lock_text)
    for registry in (cargo_home / "registry/src").glob("*"):
        for name, version in packages:
            crate = registry / (name + "-" + version)
            copy_notices(crate, "dependencies/" + crate.name)
    git_revisions = set(re.findall(r'source = "git\+[^"\n]+#([0-9a-f]{40})"', lock_text))
    for checkout in (cargo_home / "git/checkouts").glob("*/*"):
        if not (checkout / ".git").exists(): continue
        revision = subprocess.check_output(["git", "-C", str(checkout), "rev-parse", "HEAD"], text=True).strip()
        if revision in git_revisions:
            copy_notices(checkout, "dependencies/git-" + checkout.parent.name + "-" + revision)
    if os.environ.get("PDFCRAFT_OCR_MODELS_DIR", "") not in ("", "none"):
        (notices / "ocr-models").mkdir(parents=True, exist_ok=True)
        (notices / "ocr-models/ATTRIBUTION.txt").write_text(OCR_MODELS_ATTRIBUTION)
    (notices / "ANDROID-MODIFICATIONS.txt").write_text(
        "Unofficial PdfCraft Android build. Based on upstream " + PIN + "\n"
        "Modified for touch input, paragraph hit testing, compact layouts and bounded dialogs.\n"
        "The ArtCraft wordmark and logo are not embedded in this modified build.\n"
        "See provenance.json for source pins, patch and asset hashes.\n")

def package_web_assets():
    if not (WEB / "index.html").is_file():
        raise SystemExit("Web build did not produce index.html")
    destination = ASSETS / "pdfcraft"
    if destination.exists():
        shutil.rmtree(destination)
    shutil.copytree(WEB, destination)
    package_notices()
    provenance = {
        "upstream_commit": PIN, "fonts_commit": FONTS_PIN,
        "phone_patch_sha256": sha(PATCH), "cargo_lock_sha256": sha(SOURCE / "Cargo.lock"),
        "modified_upstream": True,
        "wasm_simd": os.environ.get("PDFCRAFT_WASM_SIMD") == "1",
        "ocr_models": OCR_MODELS if os.environ.get("PDFCRAFT_OCR_MODELS_DIR", "") not in ("", "none") else None,
        "assets": {str(p.relative_to(destination)): sha(p) for p in sorted(destination.rglob("*")) if p.is_file()},
    }
    (ASSETS / "provenance.json").write_text(json.dumps(provenance, indent=2) + "\n")

def main():
    run(sys.executable, ROOT / "scripts/verify-upstream.py", UPSTREAM)
    models = ocr_models_dir()  # check before the long build
    fonts_head = subprocess.check_output(["git", "-C", str(FONTS), "rev-parse", "HEAD"], text=True).strip()
    if fonts_head != FONTS_PIN:
        raise SystemExit("craft-fonts does not match the pinned revision")
    if subprocess.check_output(["git", "-C", str(FONTS), "status", "--porcelain", "--untracked-files=normal"], text=True).strip():
        raise SystemExit("craft-fonts must be clean so the recorded pin identifies the actual build input")
    if not PATCH.is_file():
        raise SystemExit("Missing patches/pdfcraft-fold7.patch")
    if SOURCE.exists():
        shutil.rmtree(SOURCE)  # only this script's generated build copy
    SOURCE.parent.mkdir(parents=True, exist_ok=True)
    run("git", "clone", "--no-hardlinks", "--no-checkout", UPSTREAM, SOURCE)
    run("git", "-C", SOURCE, "checkout", "--detach", PIN)
    run("git", "-C", SOURCE, "apply", "--check", PATCH)
    run("git", "-C", SOURCE, "apply", PATCH)
    env = dict(os.environ, CRAFT_FONTS_DIR=str(FONTS), CRAFT_FONTS_REQUIRED="1")
    env["NO_COLOR"] = "true"  # Trunk 0.21 expects a boolean, not the common NO_COLOR=1.
    env["CARGO_TARGET_DIR"] = os.environ.get("PDFCRAFT_CARGO_TARGET_DIR", str(ROOT / "build/rust-target"))
    # Optional: WASM SIMD makes text recognition several times faster (Android System WebView
    # has supported it since Chrome 91). Off unless PDFCRAFT_WASM_SIMD=1.
    if os.environ.get("PDFCRAFT_WASM_SIMD") == "1":
        env["CARGO_TARGET_WASM32_UNKNOWN_UNKNOWN_RUSTFLAGS"] = "-C target-feature=+simd128"
    if models is not None:
        env["PDFCRAFT_EMBED_MODELS_DIR"] = str(models)
    else:
        env.pop("PDFCRAFT_EMBED_MODELS_DIR", None)
    run("trunk", "build", "--skip-version-check", "--release", "--locked", "--dist", WEB, cwd=SOURCE / "apps/pdfcraft-web", env=env)
    package_web_assets()
    run(sys.executable, ROOT / "scripts/verify-upstream.py", UPSTREAM)
    print("Patched web assets and provenance ready. Run Android tests, lint and assembleDebug.")

if __name__ == "__main__":
    main()
