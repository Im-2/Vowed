# Copies the throwaway devnet deployer key (WSL) to the gitignored backend/.devnet folder. Never prints the key.
mkdir -p /mnt/c/Users/hp/Vowed/backend/.devnet
cp ~/.config/solana/id.json /mnt/c/Users/hp/Vowed/backend/.devnet/deployer.json && echo "deployer key copied to backend/.devnet/deployer.json (gitignored)"
