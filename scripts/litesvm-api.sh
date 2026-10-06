. "$(dirname "$0")/wsl-env.sh"
cd ~/vowed-program && cargo fetch >/dev/null 2>&1
d=$(ls -d ~/.cargo/registry/src/*/litesvm-0.17* | head -1); echo "$d"
grep -n 'pub fn ' $d/src/lib.rs | grep -iE 'upgrad|add_program|set_sysvar|get_sysvar|airdrop|send_transaction|expire|latest_blockhash|set_account|get_account|new\(|with_' | head -40
t=$(ls -d ~/.cargo/registry/src/*/litesvm-token-0.17* | head -1); echo "$t"; ls $t/src
grep -rn 'pub fn ' $t/src/create_mint.rs $t/src/create_account.rs $t/src/create_ata.rs $t/src/mint_to.rs 2>/dev/null | head -30
