#!/bin/bash
# build-web.sh — Reproduce the PdfCraft WASM browser build from the pinned upstream commit.
#
# This script does NOT modify the upstream repository. It builds the existing
# web target using Trunk and copies the output to build/web/.
#
# Prerequisites:
#   - rustup with wasm32-unknown-unknown target installed
#   - trunk 0.21.14 installed (cargo install trunk --version 0.21.14 --locked)
#   - craft-fonts repo cloned at commit 8dcdacd5153e64560d109541a47d806f26f048c0
#
# Usage: ./scripts/build-web.sh
#
set -euo pipefail

# --- Resolve workspace paths ---
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKSPACE="$(cd "$SCRIPT_DIR/.." && pwd)"
UPSTREAM="$WORKSPACE/pdfcraft-upstream"
CRAFT_FONTS="$WORKSPACE/craft-fonts"
BUILD_WEB="$WORKSPACE/build/web"
BUILD_TARGET="$WORKSPACE/build/rust-target"

# --- Verify upstream is at the pinned commit ---
PINNED_COMMIT="bbbe60015c571ebfc39708ee07777b89e267122a"
ACTUAL_COMMIT="$(git -C "$UPSTREAM" rev-parse HEAD)"
if [ "$ACTUAL_COMMIT" != "$PINNED_COMMIT" ]; then
    echo "ERROR: upstream is at $ACTUAL_COMMIT, expected $PINNED_COMMIT"
    exit 1
fi

if ! git -C "$UPSTREAM" diff --quiet HEAD; then
    echo "ERROR: upstream working tree is not clean"
    exit 1
fi

echo "✓ Upstream verified at $PINNED_COMMIT"

# --- Verify craft-fonts is at the pinned commit ---
PINNED_FONTS_COMMIT="8dcdacd5153e64560d109541a47d806f26f048c0"
ACTUAL_FONTS_COMMIT="$(git -C "$CRAFT_FONTS" rev-parse HEAD 2>/dev/null || echo 'NOT_FOUND')"
if [ "$ACTUAL_FONTS_COMMIT" != "$PINNED_FONTS_COMMIT" ]; then
    echo "ERROR: craft-fonts is at $ACTUAL_FONTS_COMMIT, expected $PINNED_FONTS_COMMIT"
    exit 1
fi
echo "✓ craft-fonts verified at $PINNED_FONTS_COMMIT"

# --- Verify wasm32 target is installed ---
if ! rustup target list --installed | grep -q "wasm32-unknown-unknown"; then
    echo "ERROR: wasm32-unknown-unknown target not installed."
    echo "Run: rustup target add wasm32-unknown-unknown"
    exit 1
fi
echo "✓ wasm32-unknown-unknown target installed"

# --- Verify trunk is installed ---
if ! command -v trunk &>/dev/null; then
    echo "ERROR: trunk not found in PATH."
    echo "Run: cargo install trunk --version 0.21.14 --locked"
    exit 1
fi
TRUNK_VERSION="$(trunk --version)"
echo "✓ trunk: $TRUNK_VERSION"

# --- Create output directories ---
mkdir -p "$BUILD_WEB" "$BUILD_TARGET"

# --- Build the WASM web app ---
echo ""
echo "Building PdfCraft web target (this may take 5-15 minutes)..."
echo ""

export CRAFT_FONTS_DIR="$CRAFT_FONTS"
export CRAFT_FONTS_REQUIRED=1
export CARGO_TARGET_DIR="$BUILD_TARGET"

cd "$UPSTREAM/apps/pdfcraft-web"
trunk build --release --locked --dist "$BUILD_WEB"

# --- Verify the build output ---
if [ ! -f "$BUILD_WEB/index.html" ]; then
    echo "ERROR: build output index.html not found at $BUILD_WEB/index.html"
    exit 1
fi

echo ""
echo "✓ Web build complete at $BUILD_WEB"
echo ""
echo "Output files:"
ls -la "$BUILD_WEB/"
