#!/bin/bash
# setup-trunk.sh — Download and install pre-built trunk binary for macOS x86_64
set -euo pipefail

TRUNK_VERSION="0.21.14"
ARCH=$(uname -m)

echo "Architecture: $ARCH"

if [ "$ARCH" = "x86_64" ]; then
    TRUNK_ARCH="x86_64-apple-darwin"
elif [ "$ARCH" = "arm64" ]; then
    TRUNK_ARCH="aarch64-apple-darwin"
else
    echo "Unknown architecture: $ARCH"
    exit 1
fi

TRUNK_URL="https://github.com/trunk-rs/trunk/releases/download/v${TRUNK_VERSION}/trunk-${TRUNK_ARCH}.tar.gz"
echo "Downloading from: $TRUNK_URL"

curl -fsSL "$TRUNK_URL" -o /tmp/trunk.tar.gz
tar -xzf /tmp/trunk.tar.gz -C "$HOME/.cargo/bin" trunk
rm /tmp/trunk.tar.gz

trunk --version
echo "TRUNK_INSTALLED"
