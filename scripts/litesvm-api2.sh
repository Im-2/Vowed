d=$(ls -d ~/.cargo/registry/src/*/litesvm-0.17* | head -1)
sed -n 1020,1060p $d/src/lib.rs; sed -n 1140,1215p $d/src/lib.rs; grep -n "loader_v3\|upgradeable" $d/src/lib.rs | head -20; grep -n "^\[dependencies\.\(solana-address\|solana-keypair\|solana-transaction\|solana-message\|solana-clock\)\]" -A3 $d/Cargo.toml | head -30
