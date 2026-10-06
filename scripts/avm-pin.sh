. "$(dirname "$0")/wsl-env.sh"
cargo install --git https://github.com/otter-sec/anchor --tag v1.2.0 avm --locked --force 2>&1 | tail -4
avm install 1.2.0 --force 2>&1 | tail -3 || true
avm use 1.2.0
echo "--- versions"; avm --version; anchor --version
