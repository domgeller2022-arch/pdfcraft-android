#!/usr/bin/env python3
"""
verify-upstream.py — Verify the PdfCraft upstream checkout remains unchanged.

Usage:
    python3 scripts/verify-upstream.py [UPSTREAM_DIR] [PINNED_COMMIT] [EXPECTED_CARGO_LOCK_HASH]

Checks performed:
  1. HEAD equals the pinned commit (not just a clean diff — a local commit could
     advance HEAD without touching tracked file content).
  2. git status --porcelain is empty: no modified, deleted, or staged tracked files.
  3. Cargo.lock SHA-256 matches the expected hash.
  4. No unexpected untracked source/config files that could indicate manual edits
     (generated build products must be outside upstream).

Exit codes:
  0 — all checks passed
  1 — one or more checks failed
"""

import hashlib
import subprocess
import sys
import os


def run_git(args):
    """Run a git command in the upstream directory and return stripped stdout."""
    result = subprocess.run(
        ["git", "-C", UPSTREAM] + args,
        capture_output=True, text=True, check=True
    )
    return result.stdout.strip()


def sha256_file(path):
    """Compute SHA-256 hash of a file."""
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            chunk = f.read(65536)
            if not chunk:
                break
            h.update(chunk)
    return h.hexdigest()


def main():
    global UPSTREAM

    UPSTREAM = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        os.path.dirname(__file__), "..", "..", "pdfcraft-upstream"
    )
    UPSTREAM = os.path.abspath(UPSTREAM)

    PINNED_COMMIT = sys.argv[2] if len(sys.argv) > 2 else \
        "bbbe60015c571ebfc39708ee07777b89e267122a"

    EXPECTED_CARGO_LOCK_HASH = sys.argv[3] if len(sys.argv) > 3 else \
        "2df3987bb56b353fbc6ac3377a8c46371c04c8d52ea01bd9363e97c8a6d86ae1"

    failures = []
    passes = []

    # --- Check 1: HEAD equals the pinned commit ---
    head = run_git(["rev-parse", "HEAD"])
    if head == PINNED_COMMIT:
        passes.append(f"HEAD matches pinned commit: {PINNED_COMMIT}")
    else:
        failures.append(f"HEAD is {head}, expected {PINNED_COMMIT}")

    # --- Check 2: git status is clean ---
    status = run_git(["status", "--porcelain=v1"])
    if status == "":
        passes.append("Working tree is clean (no modified/deleted/staged tracked files)")
    else:
        # Parse porcelain output for details
        untracked = []
        modified = []
        for line in status.splitlines():
            if line.startswith("??"):
                untracked.append(line[3:])
            else:
                modified.append(line)
        if modified:
            failures.append(f"Modified/deleted/staged tracked files:\n" +
                            "\n".join(f"  {m}" for m in modified))
        # Untracked files in source/config areas are flagged as unexpected
        source_patterns = [".rs", ".toml", ".json", ".html", ".css", ".js", ".md",
                           ".lock", ".txt", ".sh", ".yml", ".yaml"]
        unexpected_untracked = [f for f in untracked
                                if any(f.endswith(p) for p in source_patterns)]
        if unexpected_untracked:
            failures.append(f"Unexpected untracked source/config files:\n" +
                           "\n".join(f"  {f}" for f in unexpected_untracked))
        else:
            # Only build artifacts in target/dist/etc. are acceptable as untracked
            passes.append("No unexpected untracked source/config files")

    # --- Check 3: Cargo.lock hash matches ---
    cargo_lock_path = os.path.join(UPSTREAM, "Cargo.lock")
    if os.path.exists(cargo_lock_path):
        actual_hash = sha256_file(cargo_lock_path)
        if actual_hash == EXPECTED_CARGO_LOCK_HASH:
            passes.append(f"Cargo.lock SHA-256 matches: {EXPECTED_CARGO_LOCK_HASH}")
        else:
            failures.append(f"Cargo.lock SHA-256 mismatch: got {actual_hash}, "
                          f"expected {EXPECTED_CARGO_LOCK_HASH}")
    else:
        failures.append("Cargo.lock not found")

    # --- Report ---
    print(f"\nUpstream verification for: {UPSTREAM}")
    print(f"Pinned commit: {PINNED_COMMIT}")
    print(f"\n{'='*60}")
    print("PASSED checks:")
    for p in passes:
        print(f"  [PASS] {p}")
    if failures:
        print("FAILED checks:")
        for f in failures:
            print(f"  [FAIL] {f}")
    else:
        print("FAILED checks: none")
    print(f"{'='*60}")

    if failures:
        print(f"\nRESULT: FAIL ({len(failures)} check(s) failed)")
        sys.exit(1)
    else:
        print(f"\nRESULT: PASS ({len(passes)} check(s) passed)")
        sys.exit(0)


if __name__ == "__main__":
    main()
