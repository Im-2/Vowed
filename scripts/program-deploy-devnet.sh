#!/usr/bin/env bash
# Deploy the built program to DEVNET only. Fee payer and upgrade authority: the throwaway key in WSL (~/.config/solana/id.json).
# Refuses to run against anything but devnet. Run scripts/program-build.sh first.
set -e
. "$(dirname "$0")/wsl-env.sh"
URL="https://api.devnet.solana.com"
KP="$HOME/.config/solana/vowed-program-keypair.json"
SO="$HOME/vowed-program/target/deploy/vowed.so"
[ -f "$SO" ] || { echo "build first: scripts/program-build.sh"; exit 1; }
solana config set --url "$URL" >/dev/null
[ "$(solana config get json_rpc_url | awk '{print $NF}')" = "$URL" ] || { echo "not devnet"; exit 1; }
PAYER="$(solana-keygen pubkey "$HOME/.config/solana/id.json")"
echo "payer/upgrade authority: $PAYER"
echo "program id: $(solana-keygen pubkey "$KP")"
echo "program size: $(stat -c %s "$SO") bytes"
bal() { solana balance "$PAYER" --url "$URL" | awk '{print $1}'; }
echo "balance: $(bal) SOL"
# No automatic airdrops: the faucet is rate limited and funding is done by a human (faucet.solana.com).
echo "balance: $(bal) SOL"
solana program deploy "$SO" --program-id "$KP" --keypair "$HOME/.config/solana/id.json" --url "$URL" 2>&1 | tail -5
PID="$(solana-keygen pubkey "$KP")"
solana program show "$PID" --url "$URL"
solana program dump "$PID" /tmp/vowed-onchain.so --url "$URL" >/dev/null
# on-chain data is padded; compare the first N bytes where N = local size
N=$(stat -c %s "$SO")
echo "local   sha256: $(sha256sum "$SO" | cut -d' ' -f1)"
echo "onchain sha256: $(head -c "$N" /tmp/vowed-onchain.so | sha256sum | cut -d' ' -f1)"
