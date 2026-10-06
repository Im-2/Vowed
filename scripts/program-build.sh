#!/usr/bin/env bash
# Build and test the Anchor program from WSL. Builds on the Linux filesystem (fast) by syncing the sources.
set -e
. "$(dirname "$0")/wsl-env.sh"
SRC="$(cd "$(dirname "$0")/../programs/vowed" && pwd)"
WORK="$HOME/vowed-program"
mkdir -p "$WORK"
rsync -a --delete --exclude target --exclude .anchor "$SRC"/ "$WORK"/
cd "$WORK"
[ -f "$HOME/.config/solana/id.json" ] || solana-keygen new --no-bip39-passphrase --silent -o "$HOME/.config/solana/id.json"  # throwaway local test key, lives outside the repo
anchor test --validator legacy
