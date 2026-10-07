/**
 * Phase 8 devnet check for SKR (our own TEST SKR token on devnet, no value): `cd backend; npx tsx scripts/gate-skr.ts`
 *
 *  1. creates the throwaway rewards wallet (backend/.devnet/rewards.json) if needed, tops it up with a little SOL from the deployer (no
 *     faucet) and puts test SKR into its token account using the faucet key (the mint authority);
 *  2. runs the REAL weekly payout code against devnet: the challenge rows that decide who is eligible are seeded in a throwaway in-memory
 *     database (a real week of check-ins would take a week), but the SKR transfer to the winner is a real devnet transaction;
 *  3. buys a streak freeze: bob builds and signs the real SKR payment, and the server code confirms it through the RPC node's parsed
 *     transaction, records the freeze, and refuses to reuse the payment.
 * Prints addresses, amounts and signatures only. No key is printed.
 */
import { readFileSync, existsSync, writeFileSync } from "node:fs";
import { Connection, Keypair, PublicKey, SystemProgram, Transaction, sendAndConfirmTransaction, LAMPORTS_PER_SOL } from "@solana/web3.js";
import { Web3Chain } from "../src/chain/web3chain.js";
import { signAndSend } from "../src/chain/tx.js";
import { loadConfig } from "../src/config.js";
import { createServices } from "../src/server.js";
import { buildFreezeTx, redeemFreeze } from "../src/perks/freeze.js";
import { runWeeklyRewards, standingsForWeek, WEEK_SECONDS } from "../src/rewards/service.js";
import { ataAddress, ixCreateAtaIdempotent, ixMintTo, tokenAmount } from "../src/util/token.js";

const DIR = new URL("../.devnet/", import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, "$1");
const RPC = process.env.RPC_URL ?? "https://api.devnet.solana.com";
const conn = new Connection(RPC, "confirmed");
const load = (f: string) => Keypair.fromSecretKey(Uint8Array.from(JSON.parse(readFileSync(`${DIR}${f}`, "utf8")) as number[]));
const pubOf = (f: string) => load(f).publicKey;
const say = (k: string, v: unknown) => console.log(`${k.padEnd(36)} ${typeof v === "string" ? v : JSON.stringify(v)}`);

const deployer = load("deployer.json");
const faucet = load("faucet.json");
const bob = load("bob.json");
const skr = pubOf("mint-skr-test.json");
if (!existsSync(`${DIR}rewards.json`)) writeFileSync(`${DIR}rewards.json`, JSON.stringify([...Keypair.generate().secretKey]));
const rewards = load("rewards.json");
say("rewards wallet", rewards.publicKey.toBase58());

// ---- 1. fund the rewards wallet
const bal = await conn.getBalance(rewards.publicKey);
if (bal < 0.02 * LAMPORTS_PER_SOL) {
  await sendAndConfirmTransaction(conn, new Transaction().add(SystemProgram.transfer({ fromPubkey: deployer.publicKey, toPubkey: rewards.publicKey, lamports: Math.round(0.02 * LAMPORTS_PER_SOL) - bal })), [deployer]);
  say("SOL sent from the deployer", `${((Math.round(0.02 * LAMPORTS_PER_SOL) - bal) / LAMPORTS_PER_SOL).toFixed(4)} SOL`);
}
const chain = new Web3Chain(RPC);
const vault = ataAddress(rewards.publicKey, skr);
const vaultAcc = await chain.getAccount(vault.toBase58());
if (!vaultAcc || tokenAmount(vaultAcc.data) < 50_000_000n) {
  await signAndSend(chain, [ixCreateAtaIdempotent(faucet.publicKey, rewards.publicKey, skr), ixMintTo(skr, vault, faucet.publicKey, 100_000_000n, 6)], [faucet]);
  say("test SKR minted into the vault", "100 tSKR (faucet key is the mint authority)");
}
const skrOf = async (w: PublicKey) => {
  const a = await chain.getAccount(ataAddress(w, skr).toBase58());
  return a ? tokenAmount(a.data) : 0n;
};
say("vault holds", `${Number(await skrOf(rewards.publicKey)) / 1e6} tSKR`);

// ---- services against devnet, with a throwaway database
const cfg = loadConfig({
  JWT_SECRET: "x".repeat(40), ORACLE_SECRET_KEY: JSON.stringify([...load("oracle.json").secretKey]), CRANK_SECRET_KEY: JSON.stringify([...load("crank.json").secretKey]),
  DATABASE_PATH: ":memory:", RPC_URL: RPC, REWARDS_SECRET_KEY: JSON.stringify([...rewards.secretKey]), FAUCET_SKR_MINT: skr.toBase58(), FAUCET_USDC_MINT: pubOf("mint-usdc-test.json").toBase58(),
});
const s = createServices(cfg);
const now = chain.nowSec();

function seedPool(pool: string, startTs: number, durationDays: number) {
  s.db
    .prepare(
      `INSERT INTO challenges (pool, creator, pool_id, mint, vault, kind, mode, penalty_bps, fee_bps, start_ts, end_ts, join_deadline_ts, settle_after_ts, duration_days, required_days, goal_hash, max_participants, participant_count, settled_count, pending_claims, total_deposits, total_forfeit, total_success_stake, distributable, status, plan_json, squad_id, updated_at)
       VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`,
    )
    .run(pool, "creator", "1", skr.toBase58(), "vault", "Open", "Soft", 3000, 0, startTs, startTs + durationDays * 86400, startTs + 3600, startTs + durationDays * 86400 + 7200, durationDays, 3, "0".repeat(64), 50, 2, 0, 0, "0", "0", "0", "0", "Open", null, null, now);
}
function seedPart(pool: string, wallet: string, bitmap: bigint, days: number) {
  s.db.prepare("INSERT INTO participants (pool, wallet, stake, tz_offset_minutes, checkin_bitmap, days_completed, status, device_key_hash, updated_at) VALUES (?,?,?,?,?,?,?,?,?)").run(pool, wallet, "1000000", 0, bitmap.toString(), days, "Active", "d".repeat(64), now);
}

// ---- 2. weekly payout (real transfer, seeded eligibility)
const week = Math.floor(now / WEEK_SECONDS) - 1;
const weekStart = week * WEEK_SECONDS;
const poolA = Keypair.generate().publicKey.toBase58();
seedPool(poolA, weekStart, 7);
seedPart(poolA, bob.publicKey.toBase58(), 0b1111100n, 5); // days 2..6 done: a streak of 5 at the end of the week
seedPart(poolA, Keypair.generate().publicKey.toBase58(), 0b1000000n, 1); // someone else with a streak of 1 (under the minimum)
say("standings for the week", standingsForWeek(s, week).map((x) => ({ wallet: x.wallet.slice(0, 6) + "…", streak: x.streak })));
const before = await skrOf(bob.publicKey);
const payout = await runWeeklyRewards(s, week);
say("payout", { week, paid: payout.paid.map((p) => ({ wallet: p.wallet.slice(0, 6) + "…", rank: p.rank, tSKR: Number(p.amount) / 1e6, signature: p.signature.slice(0, 16) + "…" })), errors: payout.errors, skipped: payout.skipped.length });
const after = await skrOf(bob.publicKey);
say("bob's tSKR before -> after", `${Number(before) / 1e6} -> ${Number(after) / 1e6}`);
const again = await runWeeklyRewards(s, week);
say("second run pays nobody again", again.paid.length === 0);

// ---- 3. streak freeze bought with real SKR
const dayStart = Math.floor(now / 86_400) * 86_400 - 4 * 86_400; // a UTC midnight four days ago: today is day 4
const poolB = Keypair.generate().publicKey.toBase58();
seedPool(poolB, dayStart, 6);
seedPart(poolB, bob.publicKey.toBase58(), 0b1100n, 2); // day 1 was missed, days 2 and 3 were done
seedPart(poolB, Keypair.generate().publicKey.toBase58(), 0n, 0);
const q = await buildFreezeTx(s, bob.publicKey.toBase58(), poolB, 1);
say("freeze quote", { price: Number(q.price) / 1e6 + " tSKR", payee: q.payee.slice(0, 6) + "…", label: q.label });
const tx = Transaction.from(Buffer.from(q.transaction, "base64"));
tx.partialSign(bob);
const sent = await chain.sendAndConfirm(tx);
say("payment signature", sent.signature.slice(0, 20) + "…");
const bobBefore = after;
let result: unknown;
for (let i = 0; i < 6; i++) {
  try {
    result = await redeemFreeze(s, bob.publicKey.toBase58(), poolB, 1, sent.signature);
    break;
  } catch (e) {
    result = e instanceof Error ? e.message : String(e);
    await new Promise((r) => setTimeout(r, 2500));
  }
}
say("freeze recorded", result);
say("bob's tSKR after the payment", `${Number(bobBefore) / 1e6} -> ${Number(await skrOf(bob.publicKey)) / 1e6}`);
try {
  await redeemFreeze(s, bob.publicKey.toBase58(), poolB, 1, sent.signature);
  say("replay of the same payment", "WRONGLY ACCEPTED");
} catch (e) {
  say("replay of the same payment", `refused: ${e instanceof Error ? e.message : e}`);
}
console.log("done");
process.exit(0);
