/**
 * Generates deterministic fixtures for the Android unit tests from the backend, which is the source of truth:
 *   - real unsigned create / join / claim transactions built by the backend's own code
 *   - the addresses derived for them (config, pool, vault, participation, token accounts)
 *   - canonical-JSON and SHA-256 plan-hash vectors (also written to /shared/test-vectors/plan-hash.json and checked by a backend test)
 *
 *   npx tsx scripts/gen-android-fixtures.ts
 */
import { createHash } from "node:crypto";
import { mkdirSync, writeFileSync } from "node:fs";
import { Keypair, Transaction, type TransactionInstruction } from "@solana/web3.js";
import { canonicalJson, planHash, type GoalPlan } from "../src/domain/plan.js";
import { VowedProgram } from "../src/program/client.js";
import { ataAddress, ixCreateAtaIdempotent, ixTransferChecked } from "../src/util/token.js";

const seedKey = (b: number) => Keypair.fromSeed(new Uint8Array(32).fill(b));
const wallet = seedKey(1);
const mint = seedKey(2);
const other = seedKey(3);
const program = new VowedProgram();
const zeroHash = "11111111111111111111111111111111"; // 32 zero bytes: a fixed recent blockhash

const plan = (over: Partial<GoalPlan> = {}): GoalPlan => ({
  title: "20 squats a day",
  category: "fitness",
  cadence: { periodDays: 1, totalDays: 2, requiredDays: 2 },
  target: { metric: "squats", value: 20, unit: "reps", direction: "atLeast" },
  proofMethods: [{ type: "CAMERA_POSE", params: {}, trustTier: "high" }],
  window: null,
  difficulty: 2,
  verifiable: true,
  unverifiableReason: null,
  suggestedAlternative: null,
  clarifyingQuestions: [],
  ...over,
});

const plans: GoalPlan[] = [
  plan(),
  plan({ title: "Walk 8000 steps", category: "steps", target: { metric: "steps", value: 8000, unit: "steps", direction: "atLeast" }, proofMethods: [{ type: "STEPS", params: {}, trustTier: "medium" }], difficulty: 3 }),
  plan({ title: "Study for 2 hours", category: "study", target: { metric: "focus", value: 2, unit: "hours", direction: "atLeast" }, proofMethods: [{ type: "FOCUS_TIMER", params: { app: "Vowed" }, trustTier: "medium" }], window: { startLocalTime: "09:00", endLocalTime: "21:00" }, cadence: { periodDays: 1, totalDays: 7, requiredDays: 5 } }),
  plan({ title: "No TikTok after 10pm", category: "detox", target: { metric: "tiktok", value: 0, unit: "minutes", direction: "atMost" }, proofMethods: [{ type: "NO_USE_WINDOW", params: { packages: ["com.zhiliaoapp.musically"] }, trustTier: "high" }], window: { startLocalTime: "22:00", endLocalTime: "23:59" } }),
];

const thePlan = plans[0]!;
const goalHash = planHash(thePlan);
const poolId = 123_456_789n;
const pool = program.poolPda(wallet.publicKey, poolId);
const buildTx = (ixs: TransactionInstruction[]) => {
  const tx = new Transaction({ feePayer: wallet.publicKey, blockhash: zeroHash, lastValidBlockHeight: 1 });
  tx.add(...ixs);
  return tx.serialize({ requireAllSignatures: false, verifySignatures: false }).toString("base64");
};

const createIx = program.ixCreatePool(wallet.publicKey, mint.publicKey, {
  pool_id: poolId, kind: "Squad", mode: "Hard", penalty_bps: 10_000, start_ts: 1_800_000_000n, duration_days: 2, required_days: 2,
  goal_hash: Buffer.from(goalHash, "hex"), join_window_secs: 60n, max_participants: 10, demo_day_secs: 60,
});
const deviceKeyHash = createHash("sha256").update("fixture-device").digest("hex");
const ref = { key: pool, mint: mint.publicKey, vault: program.vaultPda(pool) };
const userToken = ataAddress(wallet.publicKey, mint.publicKey);
const joinIx = program.ixJoinPool(wallet.publicKey, ref, userToken, 4_000_000n, 60, Buffer.from(deviceKeyHash, "hex"));
const claimIxs = [ixCreateAtaIdempotent(wallet.publicKey, wallet.publicKey, mint.publicKey), program.ixClaim(wallet.publicKey, ref, userToken)];

const payee = seedKey(4);
const freezeIxs = [
  ixCreateAtaIdempotent(wallet.publicKey, payee.publicKey, mint.publicKey),
  ixTransferChecked(ataAddress(wallet.publicKey, mint.publicKey), mint.publicKey, ataAddress(payee.publicKey, mint.publicKey), wallet.publicKey, 1_000_000n, 6),
];

const fixtures = {
  generatedBy: "backend/scripts/gen-android-fixtures.ts (deterministic; do not edit by hand)",
  programId: program.programId.toBase58(),
  wallet: wallet.publicKey.toBase58(),
  otherWallet: other.publicKey.toBase58(),
  mint: mint.publicKey.toBase58(),
  config: program.configPda().toBase58(),
  pool: pool.toBase58(),
  vault: ref.vault.toBase58(),
  participation: program.participationPda(pool, wallet.publicKey).toBase58(),
  userToken: userToken.toBase58(),
  tokenProgram: "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",
  systemProgram: "11111111111111111111111111111111",
  ataProgram: "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL",
  poolId: poolId.toString(),
  goalHash,
  deviceKeyHash,
  plan: thePlan,
  create: { tx: buildTx([createIx]), expected: { mode: "Hard", penaltyBps: 10_000, startTs: 1_800_000_000, durationDays: 2, requiredDays: 2, joinWindowSecs: 60, maxParticipants: 10, demoDaySecs: 60 } },
  join: { tx: buildTx([joinIx]), expected: { stake: "4000000", tzOffsetMinutes: 60 } },
  claim: { tx: buildTx(claimIxs) },
  freeze: { tx: buildTx(freezeIxs), payee: payee.publicKey.toBase58(), price: "1000000" },
  // addresses of unrelated accounts, used to tamper with transactions in tests
  decoys: { vault: program.vaultPda(program.poolPda(other.publicKey, 1n)).toBase58(), token: ataAddress(other.publicKey, mint.publicKey).toBase58() },
};

const planVectors = plans.map((p) => ({ plan: p, canonical: canonicalJson(p), sha256: planHash(p) }));
// key order and whitespace must not matter: a shuffled copy has the same hash
const shuffled = JSON.parse(JSON.stringify(plans[0]), (_k, v) => (v && typeof v === "object" && !Array.isArray(v) ? Object.fromEntries(Object.entries(v).reverse()) : v));
planVectors.push({ plan: shuffled, canonical: canonicalJson(shuffled), sha256: planHash(shuffled) });

const root = new URL("../../", import.meta.url);
mkdirSync(new URL("android/app/src/test/resources/", root), { recursive: true });
writeFileSync(new URL("android/app/src/test/resources/vowed-fixtures.json", root), JSON.stringify(fixtures, null, 1) + "\n");
writeFileSync(new URL("shared/test-vectors/plan-hash.json", root), JSON.stringify({ note: "canonical JSON (sorted keys, no whitespace) and sha256 of GoalPlans; amounts are integers", cases: planVectors }, null, 1) + "\n");
console.log(`wrote Android fixtures and ${planVectors.length} plan-hash vectors; create tx ${Buffer.from(fixtures.create.tx, "base64").length} bytes, pool ${fixtures.pool}`);
