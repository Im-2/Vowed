#!/usr/bin/env bash
# Build the Anchor program (SBF + IDL) and run all tests (unit + LiteSVM integration) from WSL.
# Sources are synced to the Linux filesystem for speed; the program keypair lives outside the repo.
set -e
. "$(dirname "$0")/wsl-env.sh"
SRC="$(cd "$(dirname "$0")/../programs/vowed" && pwd)"
WORK="$HOME/vowed-program"
mkdir -p "$WORK" "$WORK/target/deploy"
rsync -a --delete --exclude target --exclude .anchor "$SRC"/ "$WORK"/
mkdir -p "$WORK/shared" && rsync -a --delete "$SRC/../../shared/" "$WORK/shared/"
cd "$WORK"
[ -f "$HOME/.config/solana/id.json" ] || solana-keygen new --no-bip39-passphrase --silent -o "$HOME/.config/solana/id.json"  # throwaway local test key, outside the repo
[ -f "$HOME/.config/solana/vowed-program-keypair.json" ] && cp "$HOME/.config/solana/vowed-program-keypair.json" target/deploy/vowed-keypair.json
anchor build
cargo test --workspace
# Export the IDL next to the backend (committed, so the backend and app can use it without WSL).
mkdir -p "$SRC/idl"; cp target/idl/vowed.json "$SRC/idl/vowed.json"
