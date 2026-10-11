/**
 * Where did a wallet's devnet SOL go? Read-only: lists the wallet's recent transactions with the change in its SOL balance, the network fee it paid
 * and which program the transaction used, then adds the totals. Public data only (an address, no key), no faucet, nothing is sent.
 *
 *   npx tsx scripts/wallet-spend.ts <wallet address> [--rpc https://api.devnet.solana.com] [--limit 60]
 */
import { Connection, PublicKey } from "@solana/web3.js";

const args = process.argv.slice(2);
const wallet = args.find((a) => !a.startsWith("--"));
const opt = (name: string, dflt: string) => {
  const i = args.indexOf(`--${name}`);
  return i >= 0 && args[i + 1] ? args[i + 1]! : dflt;
};
if (!wallet) {
  console.error("usage: npx tsx scripts/wallet-spend.ts <wallet address> [--rpc URL] [--limit N]");
  process.exit(1);
}
const VOWED = "BMTXJRZ4QxzCg4UCHKo6qGGiGXKW26ARPAtPaXA8k7EL";
const names: Record<string, string> = {
  [VOWED]: "Vowed program",
  "11111111111111111111111111111111": "system",
  TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA: "token",
  ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL: "token account",
};
const sol = (l: bigint) => `${l < 0n ? "-" : "+"}${(Number(l < 0n ? -l : l) / 1e9).toFixed(6)}`;

const conn = new Connection(opt("rpc", "https://api.devnet.solana.com"), "confirmed");
const key = new PublicKey(wallet);
const bal = await conn.getBalance(key);
const sigs = await conn.getSignaturesForAddress(key, { limit: Number(opt("limit", "60")) });
console.log(`wallet ${wallet}\nbalance now ${(bal / 1e9).toFixed(6)} SOL (${bal} lamports); ${sigs.length} recent transactions, newest first\n`);
let fees = 0n;
let received = 0n;
let locked = 0n;
const rows: string[] = [];
for (const sg of sigs) {
  const tx = await conn.getTransaction(sg.signature, { maxSupportedTransactionVersion: 0, commitment: "confirmed" });
  if (!tx?.meta) continue;
  const keys = tx.transaction.message.getAccountKeys().keySegments().flat().map((k) => k.toBase58());
  const idx = keys.indexOf(wallet);
  if (idx < 0) continue;
  const delta = BigInt(tx.meta.postBalances[idx]!) - BigInt(tx.meta.preBalances[idx]!);
  const payer = idx === 0;
  const fee = payer ? BigInt(tx.meta.fee) : 0n;
  const programs = [...new Set(tx.transaction.message.compiledInstructions.map((i) => keys[i.programIdIndex]!))].map((p) => names[p] ?? p.slice(0, 6)).join("+");
  if (tx.meta.err) {
    fees += fee;
    rows.push(`${new Date((tx.blockTime ?? 0) * 1000).toISOString().slice(0, 19)}  FAILED   fee ${sol(-fee)}   ${programs}`);
    continue;
  }
  const kind = delta > 0n && !payer ? "received" : programs.includes("Vowed") ? "vowed" : programs.includes("system") && delta > 0n ? "received" : "other";
  if (kind === "received") received += delta;
  else {
    fees += fee;
    if (delta < 0n) locked += -delta - fee;
  }
  rows.push(`${new Date((tx.blockTime ?? 0) * 1000).toISOString().slice(0, 19)}  ${kind.padEnd(8)} ${sol(delta).padStart(10)} SOL  fee ${fee}  ${programs}`);
}
console.log(rows.join("\n"));
console.log(`\nreceived (gifts and transfers in)  ${sol(received)} SOL\nnetwork fees paid                  ${sol(-fees)} SOL\nlocked in accounts (rent, stake SOL) ${sol(-locked)} SOL\n(rent is held by the accounts a transaction creates; the wallet no longer holds it)`);
