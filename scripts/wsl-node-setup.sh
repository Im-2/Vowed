#!/usr/bin/env bash
# Installs a Linux Node.js (same major as the Windows one, 24.x) inside WSL for the backend LiteSVM tests.
# Uses the official tarball from nodejs.org and verifies its SHA-256 against the published SHASUMS256.txt.
set -e
VER="${1:-v24.15.0}"
DEST="$HOME/.local/node-$VER"
if [ -x "$DEST/bin/node" ]; then echo "already installed: $("$DEST/bin/node" -v)"; exit 0; fi
mkdir -p "$HOME/.local" /tmp/nodeinst && cd /tmp/nodeinst
F="node-$VER-linux-x64.tar.xz"
curl -fsSLO "https://nodejs.org/dist/$VER/$F"
curl -fsSLO "https://nodejs.org/dist/$VER/SHASUMS256.txt"
grep " $F\$" SHASUMS256.txt | sha256sum -c -
tar -xJf "$F" -C "$HOME/.local"
mv "$HOME/.local/node-$VER-linux-x64" "$DEST"
ln -sfn "$DEST" "$HOME/.local/node"
echo "installed: $("$DEST/bin/node" -v), npm $("$DEST/bin/npm" -v)"
