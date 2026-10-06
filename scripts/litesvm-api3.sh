d=$(ls -d ~/.cargo/registry/src/*/litesvm-0.17* | head -1)
sed -n 1062,1110p $d/src/lib.rs
grep -n "solana-transaction\|solana-message\|solana-keypair\|solana-instruction\|solana-pubkey\|solana-address\|solana-signer" ~/vowed-program/Cargo.lock | head -0
grep -A1 'name = "solana-transaction"' ~/vowed-program/Cargo.lock; grep -A1 'name = "solana-message"' ~/vowed-program/Cargo.lock; grep -A1 'name = "solana-instruction"' ~/vowed-program/Cargo.lock; grep -A1 'name = "solana-pubkey"' ~/vowed-program/Cargo.lock; grep -A1 'name = "solana-address"' ~/vowed-program/Cargo.lock
