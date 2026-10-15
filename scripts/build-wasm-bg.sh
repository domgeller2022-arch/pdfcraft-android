#!/bin/bash
# Use the same patched, verified build path for interactive and background builds.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "$SCRIPT_DIR/build-web.sh"
