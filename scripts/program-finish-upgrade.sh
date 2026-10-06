#!/usr/bin/env bash
# Finishes a devnet upgrade from a COMPLETE buffer (see backend/scripts/fill-buffer.ts) and verifies the onchain bytes. Devnet only; no faucet.
# usage: bash scripts/program-finish-upgrade.sh <BUFFER_ADDRESS>
set -e
. "$(dirname "$0")/wsl-env.sh"
URL="https://api.devnet.solana.com"
KP="$HOME/.config/solana/vowed-program-keypair.json"
AUTH="$HOME/.config/solana/id.json"
SO="$HOME/vowed-program/target/deploy/vowed.so"
BUF="$1"
[ -n "$BUF" ] || { echo "usage: $0 <BUFFER_ADDRESS>"; exit 1; }
PID="$(solana-keygen pubkey "$KP")"
NEW=$(stat -c %s "$SO")
LOCAL_HASH="$(sha256sum "$SO" | cut -d' ' -f1)"
echo "upgrading $PID from buffer $BUF"
solana program upgrade "$BUF" "$PID" --upgrade-authority "$AUTH" --keypair "$AUTH" --url "$URL" 2>&1 | tail -3
solana program dump "$PID" /tmp/vowed-onchain.so --url "$URL" >/dev/null
ONCHAIN="$(head -c "$NEW" /tmp/vowed-onchain.so | sha256sum | cut -d' ' -f1)"
solana program show "$PID" --url "$URL" | tail -6
echo "local   sha256: $LOCAL_HASH"
echo "onchain sha256: $ONCHAIN"
echo "deployer balance: $(solana balance "$(solana-keygen pubkey "$AUTH")" --url "$URL")"
[ "$LOCAL_HASH" = "$ONCHAIN" ] && echo "UPGRADE VERIFIED: onchain bytes equal the local build" || { echo "UPGRADE NOT VERIFIED"; exit 1; }
