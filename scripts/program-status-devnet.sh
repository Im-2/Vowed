#!/usr/bin/env bash
# Read-only: deployer balance, program account and any leftover upload buffers on devnet. Sends nothing.
. "$(dirname "$0")/wsl-env.sh"
U="https://api.devnet.solana.com"
PID="$(solana-keygen pubkey "$HOME/.config/solana/vowed-program-keypair.json")"
PAYER="$(solana-keygen pubkey "$HOME/.config/solana/id.json")"
echo "--- deployer $PAYER"; solana balance "$PAYER" --url "$U"
echo "--- program $PID"; solana program show "$PID" --url "$U" | tail -6
echo "--- upload buffers owned by this authority"; solana program show --buffers --buffer-authority "$PAYER" --url "$U"
echo "--- local binary"; sha256sum "$HOME/vowed-program/target/deploy/vowed.so"; stat -c "%s bytes" "$HOME/vowed-program/target/deploy/vowed.so"
