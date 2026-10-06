#!/usr/bin/env bash
# Installs Rust, Solana (Agave) CLI and Anchor 1.2.0 inside WSL Ubuntu. Source: anchor-lang.com/docs/installation
set -e
export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y build-essential pkg-config libudev-dev llvm libclang-dev protobuf-compiler libssl-dev curl git unzip
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
. "$HOME/.cargo/env"
sh -c "$(curl -sSfL https://release.anza.xyz/stable/install)"
export PATH="$HOME/.local/share/solana/install/active_release/bin:$PATH"
# Pinned: tag v1.2.0 = commit 84a63f9f7112b23581816436fbcf3f6c515779f9 (github.com/otter-sec/anchor)
cargo install --git https://github.com/otter-sec/anchor --tag v1.2.0 avm --locked --force
avm install 1.2.0
avm use 1.2.0
echo "=== versions ==="
rustc -V; solana --version; anchor --version
