#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKSPACE="${PDFCRAFT_WORKSPACE:-$(cd "$SCRIPT_DIR/.." && pwd)}"
UPSTREAM="${PDFCRAFT_UPSTREAM_DIR:-$WORKSPACE/pdfcraft-upstream}"
OUT_FILE="$SCRIPT_DIR/provenance-output.txt"

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
echo "=== Cargo.lock SHA-256 ==="
sha256sum Cargo.lock
echo "=== Cargo.toml SHA-256 ==="
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
} > "$OUT_FILE" 2>&1

echo "DONE" >> "$OUT_FILE"
