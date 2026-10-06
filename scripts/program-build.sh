#!/usr/bin/env bash
# Build and test the Anchor program from WSL. Builds on the Linux filesystem (fast) by syncing the sources.
set -e
. "$(dirname "$0")/wsl-env.sh"
SRC="$(cd "$(dirname "$0")/../programs/vowed" && pwd)"
WORK="$HOME/vowed-program"
mkdir -p "$WORK"
rsync -a --delete --exclude target --exclude .anchor "$SRC"/ "$WORK"/
cd "$WORK"
anchor build
cargo test
