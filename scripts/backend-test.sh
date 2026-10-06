#!/usr/bin/env bash
# Runs the whole backend test suite on Linux (WSL), including the LiteSVM tests that run the real vowed.so.
# Sources are synced to the Linux filesystem (fast installs); build the program first (scripts/program-build.sh).
set -e
. "$(dirname "$0")/wsl-env.sh"
SRC="$(cd "$(dirname "$0")/../backend" && pwd)"
SHARED="$(cd "$(dirname "$0")/../shared" && pwd)"
W="$HOME/vowed-backend"
SO="$HOME/vowed-program/target/deploy/vowed.so"
[ -f "$SO" ] || { echo "missing $SO: run scripts/program-build.sh first"; exit 1; }
mkdir -p "$W" "$HOME/vowed-shared"
rsync -a --delete --exclude node_modules --exclude dist --exclude '*.sqlite*' --exclude .devnet --exclude .env "$SRC"/ "$W"/
rsync -a --delete "$SHARED"/ "$HOME/vowed-shared"/
ln -sfn "$HOME/vowed-shared" "$HOME/shared"
cd "$W"
if [ ! -d node_modules ] || [ package-lock.json -nt node_modules/.package-lock.json ]; then npm ci --no-audit --no-fund 2>&1 | tail -3; fi
export VOWED_SO="$SO"
export VOWED_SHARED="$HOME/vowed-shared"
if [ "$#" -eq 0 ]; then npx vitest run 2>&1 | tail -60; else npx vitest run "$@" 2>&1 | tail -80; fi
