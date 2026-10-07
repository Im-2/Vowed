/**
 * DEVNET ONLY. Creates the throwaway "samples" wallet (backend/.devnet/seeder.json, git-ignored) if it does not exist and tops it up
 * from the deployer by a plain transfer, never a faucet. Prints only the public address and balances.
 *
 *   npx tsx scripts/seeder-setup.ts [--sol 0.1]
 */
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { Connection, Keypair, LAMPORTS_PER_SOL, SystemProgram, Transaction, sendAndConfirmTransaction } from "@solana/web3.js";

const DIR = new URL("../.devnet/", import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, "$1");
const i = process.argv.indexOf("--sol");
const targetSol = i >= 0 ? Number(process.argv[i + 1]) : 0.1;
if (!(targetSol > 0 && targetSol <= 0.1)) throw new Error("refusing: --sol must be above 0 and at most 0.1");
const conn = new Connection(process.env.RPC_URL ?? "https://api.devnet.solana.com", "confirmed");
const load = (f: string) => Keypair.fromSecretKey(Uint8Array.from(JSON.parse(readFileSync(`${DIR}${f}`, "utf8")) as number[]));

let seeder: Keypair;
if (existsSync(`${DIR}seeder.json`)) seeder = load("seeder.json");
else {
  seeder = Keypair.generate();
  writeFileSync(`${DIR}seeder.json`, JSON.stringify([...seeder.secretKey]));
}
const deployer = load("deployer.json");
const have = await conn.getBalance(seeder.publicKey);
const want = Math.round(targetSol * LAMPORTS_PER_SOL);
const before = await conn.getBalance(deployer.publicKey);
console.log(`seeder address: ${seeder.publicKey.toBase58()}`);
if (have >= want) console.log(`already holds ${have / LAMPORTS_PER_SOL} SOL; nothing sent`);
else {
  const tx = new Transaction().add(SystemProgram.transfer({ fromPubkey: deployer.publicKey, toPubkey: seeder.publicKey, lamports: want - have }));
  const sig = await sendAndConfirmTransaction(conn, tx, [deployer]);
  console.log(`sent ${(want - have) / LAMPORTS_PER_SOL} SOL from the deployer (${sig})`);
}
console.log(`balances: seeder ${(await conn.getBalance(seeder.publicKey)) / LAMPORTS_PER_SOL} SOL, deployer ${before / LAMPORTS_PER_SOL} SOL before, ${(await conn.getBalance(deployer.publicKey)) / LAMPORTS_PER_SOL} SOL after`);
