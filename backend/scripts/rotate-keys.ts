/**
 * DEVNET ONLY, run once. Separates the keys that will live on a hosting provider from the deployer (which stays on this PC):
 *  1. creates a dedicated faucet key (backend/.devnet/faucet.json), funds it with a small amount of SOL from the deployer (no faucet), and
 *     moves the MintTokens authority of both test mints to it;
 *  2. creates a fresh oracle key, rotates the on-chain oracle to it with update_oracle (signed by the admin = deployer), moves the old
 *     oracle's remaining SOL to the new one, and saves the old key as oracle-old.json.
 * Prints only public addresses and balances. Safe to re-run: finished steps are skipped.
 */
import { existsSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { Connection, Keypair, LAMPORTS_PER_SOL, PublicKey, SystemProgram, Transaction, TransactionInstruction, sendAndConfirmTransaction } from "@solana/web3.js";
import { VowedProgram } from "../src/program/client.js";
import { TOKEN_PROGRAM_ID } from "../src/program/client.js";

const DIR = new URL("../.devnet/", import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, "$1");
const conn = new Connection(process.env.RPC_URL ?? "https://api.devnet.solana.com", "confirmed");
const load = (f: string) => Keypair.fromSecretKey(Uint8Array.from(JSON.parse(readFileSync(`${DIR}${f}`, "utf8")) as number[]));
const save = (f: string, k: Keypair) => writeFileSync(`${DIR}${f}`, JSON.stringify([...k.secretKey]));
const sol = (l: number) => (l / LAMPORTS_PER_SOL).toFixed(4);
const FAUCET_SOL = 0.05; // pays the token-account rent (about 0.004 SOL) for each new wallet that claims: roughly a dozen claims
const ORACLE_SOL = 0.01;

const deployer = load("deployer.json");
console.log(`deployer ${deployer.publicKey.toBase58().slice(0, 6)}… balance ${sol(await conn.getBalance(deployer.publicKey))} SOL`);

// ---- 1. faucet key
let faucet: Keypair;
if (existsSync(`${DIR}faucet.json`)) faucet = load("faucet.json");
else {
  faucet = Keypair.generate();
  save("faucet.json", faucet);
}
const fb = await conn.getBalance(faucet.publicKey);
if (fb < FAUCET_SOL * LAMPORTS_PER_SOL) {
  await sendAndConfirmTransaction(conn, new Transaction().add(SystemProgram.transfer({ fromPubkey: deployer.publicKey, toPubkey: faucet.publicKey, lamports: Math.round(FAUCET_SOL * LAMPORTS_PER_SOL) - fb })), [deployer]);
}
console.log(`faucet authority ${faucet.publicKey.toBase58()} balance ${sol(await conn.getBalance(faucet.publicKey))} SOL`);

for (const f of ["mint-usdc-test.json", "mint-skr-test.json"]) {
  const mint = load(f).publicKey;
  const info = await conn.getAccountInfo(mint);
  if (!info) throw new Error(`mint ${mint.toBase58()} not found`);
  const hasAuth = info.data.readUInt32LE(0) === 1;
  const current = hasAuth ? new PublicKey(info.data.subarray(4, 36)) : null;
  if (current?.equals(faucet.publicKey)) {
    console.log(`mint ${mint.toBase58().slice(0, 6)}…: authority already the faucet key`);
    continue;
  }
  if (!current?.equals(deployer.publicKey)) throw new Error(`mint ${mint.toBase58()}: unexpected current authority ${current?.toBase58()}`);
  const data = Buffer.alloc(1 + 1 + 1 + 32);
  data[0] = 6; // SetAuthority
  data[1] = 0; // MintTokens
  data[2] = 1; // Some(new authority)
  faucet.publicKey.toBuffer().copy(data, 3);
  const ix = new TransactionInstruction({ programId: TOKEN_PROGRAM_ID, keys: [{ pubkey: mint, isSigner: false, isWritable: true }, { pubkey: deployer.publicKey, isSigner: true, isWritable: false }], data });
  const sig = await sendAndConfirmTransaction(conn, new Transaction().add(ix), [deployer]);
  console.log(`mint ${mint.toBase58().slice(0, 6)}…: authority moved to the faucet key (${sig})`);
}

// ---- 2. oracle rotation
const program = new VowedProgram();
const cfgInfo = await conn.getAccountInfo(program.configPda());
if (!cfgInfo) throw new Error("config account not found");
const cfg = program.decodeConfig(cfgInfo.data) as unknown as { admin: string; oracle: string };
if (String(cfg.admin) !== deployer.publicKey.toBase58()) throw new Error("the deployer is not the program admin");
const current = load("oracle.json");
if (String(cfg.oracle) === current.publicKey.toBase58()) {
  const fresh = Keypair.generate();
  await sendAndConfirmTransaction(conn, new Transaction().add(program.ixUpdateOracle(deployer.publicKey, fresh.publicKey)), [deployer]);
  renameSync(`${DIR}oracle.json`, `${DIR}oracle-old.json`);
  save("oracle.json", fresh);
  const old = await conn.getBalance(current.publicKey);
  const move = Math.max(0, old - 10_000); // keep a few thousand lamports for the transfer fee
  if (move > 0) await sendAndConfirmTransaction(conn, new Transaction().add(SystemProgram.transfer({ fromPubkey: current.publicKey, toPubkey: fresh.publicKey, lamports: move })), [current]);
  console.log(`oracle rotated: ${current.publicKey.toBase58().slice(0, 6)}… -> ${fresh.publicKey.toBase58()}`);
} else console.log("oracle already rotated");
const oracle = load("oracle.json");
let ob = await conn.getBalance(oracle.publicKey);
if (ob < ORACLE_SOL * LAMPORTS_PER_SOL) {
  await sendAndConfirmTransaction(conn, new Transaction().add(SystemProgram.transfer({ fromPubkey: deployer.publicKey, toPubkey: oracle.publicKey, lamports: Math.round(ORACLE_SOL * LAMPORTS_PER_SOL) - ob })), [deployer]);
  ob = await conn.getBalance(oracle.publicKey);
}
console.log(`oracle ${oracle.publicKey.toBase58()} balance ${sol(ob)} SOL; deployer now ${sol(await conn.getBalance(deployer.publicKey))} SOL`);
const after = program.decodeConfig((await conn.getAccountInfo(program.configPda()))!.data) as unknown as { oracle: string };
console.log(`on-chain oracle matches the new key: ${String(after.oracle) === oracle.publicKey.toBase58()}`);
