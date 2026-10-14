#!/bin/bash
set -e

UPSTREAM="/Users/dd/Desktop/PDF Craft for Android/pdfcraft-upstream"
cd "$UPSTREAM"

{
echo "=== COMMIT ==="
git rev-parse HEAD
echo "=== STATUS ==="
git status --porcelain=v1
echo "=== CLEAN ==="
git diff --quiet HEAD || echo "HAS_DIFF"
echo "=== TRACKED FILE COUNT ==="
git ls-files | wc -l
echo "=== CargO.lock SHA-256 ==="
sha256sum Cargo.lock
echo "=== Cargo.toml SHA-256 ==="
sha256sum Cargo.toml
echo "=== WORKSPACE Cargo.toml SHA-256 ==="
sha256sum Cargo.toml
echo "=== AGGREGATE HASH OF ALL TRACKED FILES ==="
git ls-files -z | xargs -0 sha256sum | sha256sum
echo "=== INDIVIDUAL FILE HASHES (first 100) ==="
git ls-files -z | xargs -0 sha256sum | head -100
echo "=== TOOLCHAIN ==="
rustc --version
cargo --version
echo "=== RUSTUP TARGETS ==="
rustup target list --installed 2>/dev/null || echo "no rustup"
} > /Users/dd/Desktop/PDF\ Craft\ for\ Android/pdfcraft-android/provenance-output.txt 2>&1

echo "DONE" >> /Users/dd/Desktop/PDF\ Craft\ for\ Android/pdfcraft-android/provenance-output.txt
