/**
 * Creates the dedicated devnet SOL-gift wallet (backend/.devnet/sol-faucet.json, never committed) if it does not exist, and prints its PUBLIC
 * address and balance. With --fund <SOL> it moves that much devnet SOL from the deployer (never from a public faucet). With --env it writes
 * the one Render variable line to backend/.devnet/render-env-sol-faucet.txt (a secret: paste it into Render, never into the repo or the chat).
 *   npx tsx scripts/sol-faucet-setup.ts [--fund 0.1] [--env]
 */
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { Connection, Keypair, LAMPORTS_PER_SOL, sendAndConfirmTransaction, SystemProgram, Transaction } from "@solana/web3.js";

const DIR = new URL("../.devnet/", import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, "$1");
const load = (f: string) => Keypair.fromSecretKey(Uint8Array.from(JSON.parse(readFileSync(DIR + f, "utf8")) as number[]));
const file = DIR + "sol-faucet.json";
if (!existsSync(file)) writeFileSync(file, JSON.stringify(Array.from(Keypair.generate().secretKey)));
const faucet = load("sol-faucet.json");
const conn = new Connection("https://api.devnet.solana.com", "confirmed");
const bal = async (k: Keypair) => (await conn.getBalance(k.publicKey)) / LAMPORTS_PER_SOL;

console.log("SOL-gift wallet address:", faucet.publicKey.toBase58());
const i = process.argv.indexOf("--fund");
if (i > 0) {
  const sol = Number(process.argv[i + 1]);
  if (!(sol > 0 && sol <= 1)) throw new Error("--fund takes 0 < SOL <= 1");
  const deployer = load("deployer.json");
  console.log("deployer balance before:", await bal(deployer), "SOL");
  const tx = new Transaction().add(SystemProgram.transfer({ fromPubkey: deployer.publicKey, toPubkey: faucet.publicKey, lamports: Math.round(sol * LAMPORTS_PER_SOL) }));
  const sig = await sendAndConfirmTransaction(conn, tx, [deployer]);
  console.log("sent", sol, "SOL from the deployer, signature", sig);
  console.log("deployer balance after:", await bal(deployer), "SOL");
}
console.log("SOL-gift wallet balance:", await bal(faucet), "SOL");
if (process.argv.includes("--env")) {
  writeFileSync(DIR + "render-env-sol-faucet.txt", `FAUCET_SOL_SECRET_KEY=${JSON.stringify(Array.from(faucet.secretKey))}\n`);
  console.log("wrote backend/.devnet/render-env-sol-faucet.txt (secret: paste the line into Render, do not share it)");
}
