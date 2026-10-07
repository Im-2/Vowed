# Starts the backend against devnet for the emulator (the app reaches it at http://10.0.2.2:8787).
# Keys are the throwaway devnet keys in backend/.devnet (gitignored). Nothing here is a secret that belongs in the repo.
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$dev = Join-Path $root "backend\.devnet"
$env:HOST = "127.0.0.1"
$env:PORT = "8787"
$env:NETWORK = "devnet"
$env:RUN_JOBS = "true"
$env:ORACLE_SECRET_KEY = (Get-Content (Join-Path $dev "oracle.json") -Raw)
$env:CRANK_SECRET_KEY = (Get-Content (Join-Path $dev "crank.json") -Raw)
$env:JWT_SECRET = (Get-Content (Join-Path $dev "jwt-secret.txt") -Raw).Trim()
$env:DATABASE_PATH = (Join-Path $dev "app.sqlite")
Set-Location (Join-Path $root "backend")

# Test-token faucet (devnet). The authority is the mint authority of the two test mints (the throwaway deployer key kept in backend/.devnet).
# It stays in this process's environment only; nothing is written to disk or sent to the app.
$env:FAUCET_AUTHORITY_SECRET_KEY = (Get-Content (Join-Path $dev "deployer.json") -Raw)
$env:FAUCET_USDC_MINT = (node -e "const {Keypair}=require('@solana/web3.js');const k=Keypair.fromSecretKey(Uint8Array.from(JSON.parse(require('fs').readFileSync('.devnet/mint-usdc-test.json','utf8'))));process.stdout.write(k.publicKey.toBase58())")
$env:FAUCET_SKR_MINT = (node -e "const {Keypair}=require('@solana/web3.js');const k=Keypair.fromSecretKey(Uint8Array.from(JSON.parse(require('fs').readFileSync('.devnet/mint-skr-test.json','utf8'))));process.stdout.write(k.publicKey.toBase58())")

npx tsx src/index.ts
