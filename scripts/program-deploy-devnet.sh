#!/usr/bin/env bash
# Deploy or UPGRADE the built program on DEVNET only (same program id). Fee payer and upgrade authority: the throwaway key in WSL.
# Run scripts/program-build.sh first. Never calls a faucet: if the deployer is short of SOL it stops and says how much is needed.
#
# Built for the congested public devnet RPC: an interrupted upload is RESUMED from the partly written buffer instead of starting over
# (set CLEAN=1 to close old buffers and start fresh, which refunds their SOL to the deployer), and the upload is retried a few times.
# USE_RPC=1 sends writes through the RPC instead of straight to validators (slower and more rate limited, so off by default).
set -e
. "$(dirname "$0")/wsl-env.sh"
URL="https://api.devnet.solana.com"
KP="$HOME/.config/solana/vowed-program-keypair.json"
AUTH="$HOME/.config/solana/id.json"
SO="$HOME/vowed-program/target/deploy/vowed.so"
[ -f "$SO" ] || { echo "build first: scripts/program-build.sh"; exit 1; }
solana config set --url "$URL" >/dev/null
[ "$(solana config get json_rpc_url | awk '{print $NF}')" = "$URL" ] || { echo "not devnet"; exit 1; }
PID="$(solana-keygen pubkey "$KP")"
PAYER="$(solana-keygen pubkey "$AUTH")"
NEW=$(stat -c %s "$SO")
LOCAL_HASH="$(sha256sum "$SO" | cut -d' ' -f1)"
echo "$(date -u +%H:%M:%S) program id: $PID"
echo "payer / upgrade authority: $PAYER"
echo "new binary: $NEW bytes, sha256 $LOCAL_HASH"
bal() { solana balance "$PAYER" --url "$URL" | awk '{print $1}'; }
echo "balance before: $(bal) SOL"

if [ "$CLEAN" = "1" ]; then
  echo "CLEAN=1: closing all upload buffers of this authority (refund goes to the deployer)..."
  solana program close --buffers --keypair "$AUTH" --url "$URL" --bypass-warning 2>&1 | tail -3 || true
  echo "balance after cleanup: $(bal) SOL"
fi

CUR=$(solana program show "$PID" --url "$URL" 2>/dev/null | awk '/Data Length/ {print $3}')
if [ -n "$CUR" ]; then
  echo "currently deployed account size: $CUR bytes (upgrade)"
  if [ "$NEW" -gt "$CUR" ]; then
    EXTRA=$((NEW - CUR))
    echo "extending the program account by $EXTRA bytes"
    solana program extend "$PID" "$EXTRA" --keypair "$AUTH" --url "$URL" 2>&1 | tail -2
  fi
else
  echo "not deployed yet (fresh deploy)"
fi

# An existing buffer of ours means a previous upload was interrupted: resume into it.
BUF=$(solana program show --buffers --buffer-authority "$PAYER" --url "$URL" 2>/dev/null | awk 'NR>2 && NF>=1 {print $1; exit}')
BUFARG=""
if [ -n "$BUF" ]; then
  echo "resuming the interrupted upload in buffer $BUF"
  BUFARG="--buffer $BUF"
else
  # A fresh upload needs about as much SOL as the program itself; it is refunded when the upgrade completes.
  NEED=$(awk "BEGIN{printf \"%.2f\", $NEW * 0.00000510 + 0.05}")
  if awk "BEGIN{exit !($(bal) < $NEED)}"; then
    echo "NOT ENOUGH SOL: the deployer $PAYER has $(bal) SOL and needs about $NEED SOL for the upload buffer. Fund it by hand; no faucet is called."
    exit 2
  fi
fi
RPCARG=""
[ "$USE_RPC" = "1" ] && RPCARG="--use-rpc"

ok=0
for attempt in 1 2 3 4; do
  echo "$(date -u +%H:%M:%S) upload attempt $attempt..."
  solana program deploy "$SO" --program-id "$KP" --upgrade-authority "$AUTH" --keypair "$AUTH" --url "$URL" $BUFARG $RPCARG --max-sign-attempts 50 2>&1 | tail -4 || true
  solana program dump "$PID" /tmp/vowed-onchain.so --url "$URL" >/dev/null 2>&1 || true
  if [ "$(head -c "$NEW" /tmp/vowed-onchain.so 2>/dev/null | sha256sum | cut -d' ' -f1)" = "$LOCAL_HASH" ]; then ok=1; break; fi
  # the failed attempt may have created a new buffer: resume into whichever buffer exists now
  BUF=$(solana program show --buffers --buffer-authority "$PAYER" --url "$URL" 2>/dev/null | awk 'NR>2 && NF>=1 {print $1; exit}')
  [ -n "$BUF" ] && BUFARG="--buffer $BUF"
  echo "attempt $attempt did not finish; will resume (buffer: ${BUF:-none})"
  sleep 10
done
solana program show "$PID" --url "$URL" | tail -6
echo "local   sha256: $LOCAL_HASH"
echo "onchain sha256: $(head -c "$NEW" /tmp/vowed-onchain.so | sha256sum | cut -d' ' -f1)"
echo "balance after: $(bal) SOL"
[ "$ok" = "1" ] && echo "UPGRADE VERIFIED: onchain bytes equal the local build" || { echo "UPGRADE NOT VERIFIED"; exit 1; }
