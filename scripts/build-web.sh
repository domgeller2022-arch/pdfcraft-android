#!/bin/bash
# Build the pinned, phone-patched source in an isolated copy; original upstream stays clean.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec python3 "$SCRIPT_DIR/build-patched-web.py"
