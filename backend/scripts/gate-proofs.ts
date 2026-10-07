/**
 * Phase 4 gate (devnet): every proof type produces a recorded on-chain check-in, and replay attempts are rejected.
 *
 *   cd backend; npm run gate:proofs            (needs the test wallets funded; see docs/runbook.md)
 *
 * One DEMO pool per proof type (5-minute demo days, test USDC): alice creates and joins it with a software device key,
 * a proof is built exactly the way the Android app builds it (canonical JSON, ECDSA P-256 signature), the backend
 * verifies it and the oracle writes the check-in on Solana. Then the abuse cases are tried against the first pool.
 * CAMERA_POSE was already exercised by the Phase 2 gate; the phone-side camera counter arrives in Phase 5.
 */
import { createHash, createPrivateKey, createPublicKey, generateKeyPairSync, randomBytes, sign as cryptoSign, type KeyObject } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { Keypair, Transaction } from "@solana/web3.js";
import nacl from "tweetnacl";
import { Web3Chain } from "../src/chain/web3chain.js";
import { loadConfig } from "../src/config.js";
import { type GoalPlan, type ProofType } from "../src/domain/plan.js";
import { buildApp } from "../src/http/app.js";
import { buildSiwsMessage } from "../src/http/auth.js";
import { deviceRegistrationMessage } from "../src/http/devices.js";
import { canonicalPackageBytes } from "../src/proofs/verify.js";
import { createServices } from "../src/server.js";

const DIR = new URL("../.devnet/", import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, "$1");
mkdirSync(DIR, { recursive: true });
const RPC_URL = process.env.RPC_URL ?? "https://api.devnet.solana.com";
const explorer = (sig: string) => `https://explorer.solana.com/tx/${sig}?cluster=devnet`;
const log = (m: string) => console.log(m);
const key = (n: string) => Keypair.fromSecretKey(Uint8Array.from(JSON.parse(readFileSync(`${DIR}${n}.json`, "utf8")) as number[]));
const alice = key("alice");
const oracle = key("oracle");
const crank = key("crank");
const usdcT = key("mint-usdc-test");
const DAY = 300; // demo day length in seconds

function device(name: string): { id: string; spki: Buffer; priv: KeyObject } {
  const path = `${DIR}${name}-device.pem`;
  let priv: KeyObject;
  if (existsSync(path)) priv = createPrivateKey(readFileSync(path, "utf8"));
  else {
    const kp = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
    writeFileSync(path, kp.privateKey.export({ type: "pkcs8", format: "pem" }) as string);
    priv = kp.privateKey;
  }
  const spki = createPublicKey(priv).export({ type: "spki", format: "der" }) as Buffer;
  return { id: createHash("sha256").update(spki).digest("hex"), spki, priv };
}

type Case = { type: ProofType; title: string; target: GoalPlan["target"]; category: GoalPlan["category"]; params: Record<string, unknown>; metrics: (now: number) => Record<string, unknown>; windowSecs: number };
const CASES: Case[] = [
  { type: "SELF_ATTEST", title: "Read 20 pages", target: { metric: "pages", value: 20, unit: "pages", direction: "atLeast" }, category: "study", params: {}, metrics: () => ({ done: true }), windowSecs: 1 },
  { type: "FOCUS_TIMER", title: "Focus for 20 seconds", target: { metric: "focus", value: 20, unit: "seconds", direction: "atLeast" }, category: "study", params: {}, metrics: () => ({ focusedSeconds: 22 }), windowSecs: 30 },
  { type: "STEPS", title: "Walk 20 steps", target: { metric: "steps", value: 20, unit: "steps", direction: "atLeast" }, category: "steps", params: {}, metrics: () => ({ steps: 35 }), windowSecs: 60 },
  { type: "GEOFENCE", title: "Be at the gym for 10 seconds", target: { metric: "gym", value: 10, unit: "seconds", direction: "atLeast" }, category: "location", params: { place: "Gym", radiusM: "150" }, metrics: () => ({ inside: true, dwellSeconds: 14 }), windowSecs: 30 },
  { type: "USAGE_LIMIT", title: "Under 30 minutes on Instagram", target: { metric: "instagram", value: 30, unit: "minutes", direction: "atMost" }, category: "detox", params: { app: "Instagram" }, metrics: () => ({ usageSeconds: 420, packagesChecked: ["com.instagram.android"] }), windowSecs: 60 },
  { type: "NO_USE_WINDOW", title: "No TikTok after 10pm", target: { metric: "tiktok", value: 0, unit: "minutes", direction: "atMost" }, category: "detox", params: { app: "TikTok" }, metrics: () => ({ usageSecondsInWindow: 0, packagesChecked: ["com.zhiliaoapp.musically"] }), windowSecs: 60 },
];

async function main() {
  const chain = new Web3Chain(RPC_URL);
  const jwtPath = `${DIR}jwt-secret.txt`;
  const config = loadConfig({
    JWT_SECRET: readFileSync(jwtPath, "utf8"),
    ORACLE_SECRET_KEY: JSON.stringify(Array.from(oracle.secretKey)),
    CRANK_SECRET_KEY: JSON.stringify(Array.from(crank.secretKey)),
    DATABASE_PATH: `${DIR}gate-proofs.sqlite`,
    RPC_URL,
    NETWORK: "devnet",
  });
  const s = createServices(config);
  const app = await buildApp(s);
  const call = async (method: "GET" | "POST", url: string, token: string | null, payload?: unknown) => {
    // the public devnet RPC rate-limits now and then: retry only gateway-style failures (502/503), never a deliberate refusal
    for (let attempt = 0; ; attempt++) {
      const res = await app.inject({ method, url, headers: token ? { authorization: `Bearer ${token}` } : {}, payload: payload as object });
      if ((res.statusCode === 502 || res.statusCode === 503) && attempt < 3) {
        await new Promise((r) => setTimeout(r, 1500 * (attempt + 1)));
        continue;
      }
      return { status: res.statusCode, body: res.json() as any };
    }
  };
  const ok = async (method: "GET" | "POST", url: string, token: string | null, payload?: unknown) => {
    const r = await call(method, url, token, payload);
    if (r.status !== 200) throw new Error(`${method} ${url} -> ${r.status} ${JSON.stringify(r.body)}`);
    return r.body;
  };

  const wallet = alice.publicKey.toBase58();
  const n = await ok("POST", "/v1/auth/nonce", null, { wallet });
  const msg = new TextEncoder().encode(buildSiwsMessage({ domain: n.domain, address: wallet, statement: n.statement, uri: n.uri, nonce: n.nonce, issuedAt: n.issuedAt, expirationTime: n.expirationTime }));
  const token: string = (await ok("POST", "/v1/auth/verify", null, { wallet, message: Buffer.from(msg).toString("base64"), signature: Buffer.from(nacl.sign.detached(msg, alice.secretKey)).toString("base64") })).token;
  const dev = device("alice-proofs");
  const { challenge } = await ok("POST", "/v1/devices/challenge", token, {});
  const reg = new TextEncoder().encode(deviceRegistrationMessage(wallet, dev.id, challenge));
  await ok("POST", "/v1/devices/register", token, { devicePublicKey: dev.spki.toString("base64"), challenge, walletSignature: Buffer.from(nacl.sign.detached(reg, alice.secretKey)).toString("base64") });
  const sendTx = async (b64: string) => {
    const tx = Transaction.from(Buffer.from(b64, "base64"));
    tx.partialSign(alice);
    const res = await chain.sendAndConfirm(tx);
    await ok("POST", "/v1/challenges/sync", token, { signature: res.signature });
    return res.signature;
  };

  const startTs = chain.nowSec() + 100 + CASES.length * 25; // enough time to create and join all pools before day 0 opens
  log(`== Phase 4 gate on devnet: ${CASES.length} DEMO pools (${DAY}-second days), one per proof type ==`);
  const pools: { c: Case; pool: string }[] = [];
  for (const c of CASES) {
    const plan: GoalPlan = {
      title: c.title, category: c.category, cadence: { periodDays: 1, totalDays: 3, requiredDays: 1 }, target: c.target,
      proofMethods: [{ type: c.type, params: c.params, trustTier: "medium" }], window: null, difficulty: 2, verifiable: true,
      unverifiableReason: null, suggestedAlternative: null, clarifyingQuestions: [],
    };
    const cr = await ok("POST", "/v1/challenges/tx/create", token, { mint: usdcT.publicKey.toBase58(), kind: "Squad", mode: "Soft", penaltyBps: 3000, startTs, joinWindowSecs: DAY, plan, maxParticipants: 5, demo: { daySecs: DAY } });
    await sendTx(cr.transaction);
    const j = await ok("POST", "/v1/challenges/tx/join", token, { pool: cr.pool, stake: "100000", tzOffsetMinutes: 0, deviceId: dev.id });
    await sendTx(j.transaction);
    pools.push({ c, pool: cr.pool });
    log(`  ${c.type.padEnd(14)} pool ${cr.pool.slice(0, 8)}... created and joined (${cr.summary.label ?? "demo"})`);
  }
  const wait = startTs + 2 - chain.nowSec();
  if (wait > 0) {
    log(`waiting ${wait}s for day 0 to open...`);
    await new Promise((r) => setTimeout(r, wait * 1000));
  }

  const build = async (pool: string, c: Case, day: number) => {
    const sess = await ok("POST", "/v1/proofs/session", token, { pool, dayIndex: day, proofType: c.type });
    const now = chain.nowSec();
    const pkg = {
      sessionId: sess.sessionId, nonce: sess.nonce, challengeId: pool, dayIndex: day, proofType: c.type, metrics: c.metrics(now),
      startedAt: now - c.windowSecs - 2, endedAt: now - 1,
      evidenceHash: createHash("sha256").update(randomBytes(8)).digest("hex"), deviceKeyId: dev.id,
    };
    const signature = cryptoSign("sha256", canonicalPackageBytes(pkg), { key: dev.priv, dsaEncoding: "der" }).toString("base64");
    return { ...pkg, signature };
  };

  let failures = 0;
  const check = (label: string, cond: boolean, detail = "") => {
    log(`  ${cond ? "PASS" : "FAIL"}  ${label}${detail ? `: ${detail}` : ""}`);
    if (!cond) failures++;
  };

  // ---- abuse cases first, on the LAST pool, before its genuine proof: every one must be refused and none may burn the real proof
  log("\n-- abuse cases on the last pool (all must be refused) --");
  const target = pools[pools.length - 1]!;
  const refused = async (label: string, pkg: Record<string, unknown>, expectCode = 422) => {
    const r = await call("POST", "/v1/proofs/submit", token, pkg);
    check(label, r.status === expectCode, `${r.status} ${r.body.error?.code ?? ""} ${r.body.error?.message ?? ""}`.trim());
  };
  {
    const good = await build(target.pool, target.c, 0);
    await refused("metrics changed after signing (tamper)", { ...good, metrics: { ...good.metrics, usageSecondsInWindow: 0, tampered: true } });
  }
  {
    // signed by a different key than the one registered and joined with, claiming to be alice's device
    const stranger = device("stranger");
    const sess = await ok("POST", "/v1/proofs/session", token, { pool: target.pool, dayIndex: 0, proofType: target.c.type });
    const now = chain.nowSec();
    const pkg = { sessionId: sess.sessionId, nonce: sess.nonce, challengeId: target.pool, dayIndex: 0, proofType: target.c.type, metrics: target.c.metrics(now), startedAt: now - 5, endedAt: now - 1, evidenceHash: createHash("sha256").update("x").digest("hex"), deviceKeyId: dev.id };
    const signature = cryptoSign("sha256", canonicalPackageBytes(pkg), { key: stranger.priv, dsaEncoding: "der" }).toString("base64");
    await refused("signed by a key that is not the registered device", { ...pkg, signature });
    const sess2 = await ok("POST", "/v1/proofs/session", token, { pool: target.pool, dayIndex: 0, proofType: target.c.type });
    const pkg2 = { ...pkg, sessionId: sess2.sessionId, nonce: sess2.nonce, deviceKeyId: stranger.id };
    await refused("signed and labelled with an unregistered device", { ...pkg2, signature: cryptoSign("sha256", canonicalPackageBytes(pkg2), { key: stranger.priv, dsaEncoding: "der" }).toString("base64") });
  }
  {
    // data that misses the goal: used the blocked app
    const bad = { ...target.c, windowSecs: 60, metrics: () => ({ usageSecondsInWindow: 900, packagesChecked: ["com.zhiliaoapp.musically"] }) };
    const r = await call("POST", "/v1/proofs/submit", token, await build(target.pool, bad, 0));
    check("data that misses the goal", r.status === 422 && /blocked window/.test(r.body.error?.message ?? ""), `${r.status} ${r.body.error?.message ?? ""}`);
  }
  {
    const fut = await call("POST", "/v1/proofs/session", token, { pool: target.pool, dayIndex: 1, proofType: target.c.type });
    check("a day that has not started cannot be opened", fut.status === 409 && fut.body.error?.code === "window_closed", `${fut.status} ${fut.body.error?.code ?? ""}`);
    const wrongType = await call("POST", "/v1/proofs/session", token, { pool: target.pool, dayIndex: 0, proofType: "STEPS" });
    check("a proof type that is not in the plan is refused", wrongType.status === 400 && wrongType.body.error?.code === "proof_type_not_in_plan", `${wrongType.status} ${wrongType.body.error?.code ?? ""}`);
  }

  log("\n-- each proof type: verified by the backend and recorded on chain --");
  let firstPkg: Awaited<ReturnType<typeof build>> | null = null;
  for (const { c, pool } of pools) {
    const pkg = await build(pool, c, 0);
    const r = await call("POST", "/v1/proofs/submit", token, pkg);
    const sig = r.body.checkin?.signature as string | undefined;
    check(`${c.type} proof accepted and check-in confirmed`, r.status === 200 && r.body.checkin?.status === "confirmed" && r.body.daysCompleted === 1, sig ? explorer(sig) : JSON.stringify(r.body));
    if (!firstPkg) firstPkg = pkg;
  }

  log("\n-- replay --");
  const first = pools[0]!;
  const replay = await call("POST", "/v1/proofs/submit", token, firstPkg);
  check("replaying an accepted package returns the stored result and records nothing new", replay.status === 200 && replay.body.daysCompleted === 1 && replay.body.checkin?.status === "confirmed", `daysCompleted ${replay.body.daysCompleted}`);
  const again = await call("POST", "/v1/proofs/session", token, { pool: first.pool, dayIndex: 0, proofType: first.c.type });
  check("a new session for an already recorded day is refused", again.status === 409 && again.body.error?.code === "already_recorded", again.body.error?.code);
  const bm = await ok("GET", `/v1/challenges/${first.pool}`, token);
  check("the stored day count is still 1 after the replay", bm.participants[0].daysCompleted === 1, `daysCompleted ${bm.participants[0].daysCompleted}`);

  log(failures === 0 ? `\nGATE PASSED: ${CASES.length} proof types recorded on devnet; tamper, forged-device, missed-goal, early-day and replay attempts refused.` : `\nGATE FAILED: ${failures} check(s) failed.`);
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error(e instanceof Error ? e.message : e);
  process.exit(1);
});
