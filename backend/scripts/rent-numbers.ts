/** Read-only: the SOL a transaction needs. Account sizes come from real accounts of the Vowed program on devnet; rent from the RPC. Prints numbers only. */
import { Connection, PublicKey } from "@solana/web3.js";
const conn = new Connection("https://api.devnet.solana.com", "confirmed");
const program = new PublicKey("BMTXJRZ4QxzCg4UCHKo6qGGiGXKW26ARPAtPaXA8k7EL");
const accs = await conn.getProgramAccounts(program, { dataSlice: { offset: 0, length: 8 } });
const sizes = new Map<number, number>();
for (const a of accs) sizes.set(a.account.data.length, 0);
// getProgramAccounts with a slice hides the real length, so look at the real accounts one by one (a few are enough)
const lens = new Map<string, number>();
for (const a of accs.slice(0, 40)) {
  const full = await conn.getAccountInfo(a.pubkey, { dataSlice: { offset: 0, length: 0 } });
  void full;
}
const disc = new Map<string, { size: number; n: number }>();
for (const a of accs.slice(0, 60)) {
  const info = await conn.getAccountInfo(a.pubkey);
  if (!info) continue;
  const key = Buffer.from(info.data.subarray(0, 8)).toString("hex");
  const cur = disc.get(key) ?? { size: info.data.length, n: 0 };
  cur.n++;
  disc.set(key, cur);
}
console.log("program accounts found:", accs.length);
for (const [k, v] of disc) console.log("account type", k, "size", v.size, "bytes, seen", v.n, "rent", await conn.getMinimumBalanceForRentExemption(v.size), "lamports");
console.log("token account (165 bytes) rent", await conn.getMinimumBalanceForRentExemption(165));
console.log("system account (0 bytes) rent-exempt minimum", await conn.getMinimumBalanceForRentExemption(0));
