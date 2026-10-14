#!/bin/bash
# build-wasm-bg.sh — Build the PdfCraft WASM web app in the background.
# Runs trunk build with the correct environment variables and writes
# output to a log file that can be checked later.
set -euo pipefail

WORKSPACE="/Users/dd/Desktop/PDF Craft for Android"
UPSTREAM="$WORKSPACE/pdfcraft-upstream"
WEB_DIR="$UPSTREAM/apps/pdfcraft-web"
BUILD_WEB="$WORKSPACE/pdfcraft-android/build/web"
BUILD_TARGET="$WORKSPACE/pdfcraft-android/build/rust-target"
CRAFT_FONTS="$WORKSPACE/craft-fonts"
LOG_FILE="$WORKSPACE/pdfcraft-android/build/web-build.log"
PID_FILE="$WORKSPACE/pdfcraft-android/build/web-build.pid"

export CRAFT_FONTS_DIR="$CRAFT_FONTS"
export CRAFT_FONTS_REQUIRED=1
export CARGO_TARGET_DIR="$BUILD_TARGET"

mkdir -p "$BUILD_WEB" "$BUILD_TARGET" "$(dirname "$LOG_FILE")"

# Verify upstream is at the pinned commit
PINNED="bbbe60015c571ebfc39708ee07777b89e267122a"
ACTUAL=$(git -C "$UPSTREAM" rev-parse HEAD)
if [ "$ACTUAL" != "$PINNED" ]; then
    echo "ERROR: upstream is at $ACTUAL, expected $PINNED" > "$LOG_FILE"
    exit 1
fi

# Verify craft-fonts is at the pinned commit
FONTS_PINNED="8dcdacd5153e64560d109541a47d806f26f048c0"
FONTS_ACTUAL=$(git -C "$CRAFT_FONTS" rev-parse HEAD 2>/dev/null || echo "NOT_FOUND")
if [ "$FONTS_ACTUAL" != "$FONTS_PINNED" ]; then
    echo "ERROR: craft-fonts is at $FONTS_ACTUAL, expected $FONTS_PINNED" > "$LOG_FILE"
    exit 1
fi

echo "=== WASM Build started at $(date) ===" > "$LOG_FILE"
echo "Working directory: $WEB_DIR" >> "$LOG_FILE"
echo "Output directory: $BUILD_WEB" >> "$LOG_FILE"
echo "Rust target dir: $BUILD_TARGET" >> "$LOG_FILE"
echo "CRAFT_FONTS_DIR: $CRAFT_FONTS" >> "$LOG_FILE"
echo "" >> "$LOG_FILE"

# Start the build in the background with nohup so it survives shell exit
# trunk must be run from the web app directory (it reads Trunk.toml from CWD)
nohup bash -c '
  cd "/Users/dd/Desktop/PDF Craft for Android/pdfcraft-upstream/apps/pdfcraft-web"
  trunk build --release --locked --dist "/Users/dd/Desktop/PDF Craft for Android/pdfcraft-android/build/web"
  echo "BUILD_EXIT=$?"
' >> "$LOG_FILE" 2>&1 &

BUILD_PID=$!
echo $BUILD_PID > "$PID_FILE"
echo "WASM build started with PID $BUILD_PID"
echo "Log file: $LOG_FILE"
