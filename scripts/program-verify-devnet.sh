. "$(dirname "$0")/wsl-env.sh"
URL="https://api.devnet.solana.com"; SO="$HOME/vowed-program/target/deploy/vowed.so"
PID="$(solana-keygen pubkey "$HOME/.config/solana/vowed-program-keypair.json")"
solana program dump "$PID" /tmp/vowed-onchain.so --url "$URL" >/dev/null
N=$(stat -c %s "$SO")
echo "local   sha256: $(sha256sum "$SO" | cut -d' ' -f1)"
echo "onchain sha256: $(head -c "$N" /tmp/vowed-onchain.so | sha256sum | cut -d' ' -f1)"
solana balance "$(solana-keygen pubkey "$HOME/.config/solana/id.json")" --url "$URL"
