/**
 * Phase 2 gate: runs the whole backend against real devnet with throwaway test wallets.
 *
 *   npm run gate:devnet              # setup (idempotent), then start or finish depending on saved state
 *   npm run gate:devnet -- --plan    # only print which addresses need how much devnet SOL (sends nothing)
 *   npm run gate:devnet -- --fund    # top up the test wallets from the deployer key (never from a faucet)
 *
 * Why it runs in two stages: the program's days are real 24-hour days and settlement opens end_ts + 2h, so a pool created now
 * can only be settled about 26 hours later. Stage A (create, join, prove, check-in on chain) runs immediately; stage B (crank
 * settles, winner claims, treasury sweep, balance checks) runs when the script is invoked again after that time. State is kept
 * in backend/.devnet/gate-state.json and every step is idempotent.
 *
 * Exit codes: 0 = finished and verified, 2 = needs devnet SOL (nothing sent), 3 = stage A done, waiting for settlement time.
 */
import { createHash, createPrivateKey, createPublicKey, generateKeyPairSync, randomBytes, sign as cryptoSign, type KeyObject } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { Keypair, PublicKey, SystemProgram, Transaction } from "@solana/web3.js";
import nacl from "tweetnacl";
import { signAndSend } from "../src/chain/tx.js";
import { Web3Chain } from "../src/chain/web3chain.js";
import { loadConfig } from "../src/config.js";
import { type Chain } from "../src/chain/types.js";
import { type GoalPlan } from "../src/domain/plan.js";
import { buildSiwsMessage } from "../src/http/auth.js";
import { deviceRegistrationMessage } from "../src/http/devices.js";
import { runCrank } from "../src/jobs/crank.js";
import { pollOnce } from "../src/indexer.js";
import { VowedProgram } from "../src/program/client.js";
import { canonicalPackageBytes } from "../src/proofs/verify.js";
import { buildApp } from "../src/http/app.js";
import { createServices } from "../src/server.js";
import { ataAddress, ixCreateAtaIdempotent, ixMintTo, ixsCreateMint, tokenAmount } from "../src/util/token.js";

const DIR = fileURLToPath(new URL("../.devnet/", import.meta.url));
const args = new Set(process.argv.slice(2));
const UNIT = 1_000_000n;
const DEVNET_USDC = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU"; // Circle devnet USDC (allowed in config; we cannot mint it)
const RPC_URL = process.env.RPC_URL ?? "https://api.devnet.solana.com";
const explorer = (sig: string) => `https://explorer.solana.com/tx/${sig}?cluster=devnet`;
const log = (m: string) => console.log(m);

mkdirSync(DIR, { recursive: true });

// ------------------------------------------------------------------ keys (throwaway, gitignored)
function loadKey(name: string, mustExist = false): Keypair {
  const path = `${DIR}${name}.json`;
  if (existsSync(path)) return Keypair.fromSecretKey(Uint8Array.from(JSON.parse(readFileSync(path, "utf8")) as number[]));
  if (mustExist) throw new Error(`missing ${name}.json: run scripts/devnet-copy-deployer.sh`);
  const kp = Keypair.generate();
  writeFileSync(path, JSON.stringify(Array.from(kp.secretKey)));
  return kp;
}
const deployer = loadKey("deployer", true);
const oracle = loadKey("oracle");
const crank = loadKey("crank");
const treasury = loadKey("treasury");
const alice = loadKey("alice");
const bob = loadKey("bob");
const usdcT = loadKey("mint-usdc-test");
const skrT = loadKey("mint-skr-test");

function loadDevice(name: string): { id: string; spki: Buffer; priv: KeyObject } {
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

// ------------------------------------------------------------------ chain + services
const chain: Chain = new Web3Chain(RPC_URL);
const connection = (chain as Web3Chain).connection;
const program = new VowedProgram();

async function lamports(k: PublicKey): Promise<bigint> {
  return BigInt(await connection.getBalance(k, "confirmed"));
}

async function main() {
  log(`RPC ${RPC_URL}\nprogram ${program.programId.toBase58()}`);

  // ---- 1. funding plan (never a faucet)
  const targets: [string, Keypair, bigint][] = [
    ["alice", alice, 30_000_000n],
    ["bob", bob, 30_000_000n],
    ["oracle", oracle, 20_000_000n],
    ["crank", crank, 30_000_000n],
  ];
  const deployerBal = await lamports(deployer.publicKey);
  const shortfalls: { name: string; key: PublicKey; have: bigint; need: bigint }[] = [];
  log("\nDevnet SOL needed by the test wallets (rent for pool/vault/participation/token accounts + fees):");
  for (const [name, kp, target] of targets) {
    const have = await lamports(kp.publicKey);
    const need = have >= target ? 0n : target - have;
    log(`  ${name.padEnd(7)} ${kp.publicKey.toBase58()}  has ${Number(have) / 1e9} SOL, target ${Number(target) / 1e9}${need ? `, short ${Number(need) / 1e9}` : ", ok"}`);
    if (need) shortfalls.push({ name, key: kp.publicKey, have, need });
  }
  log(`  deployer ${deployer.publicKey.toBase58()} has ${Number(deployerBal) / 1e9} SOL (also pays mint/token-account/config rent, about 0.02 SOL)`);
  const total = shortfalls.reduce((a, s) => a + s.need, 0n);
  if (shortfalls.length) {
    if (args.has("--fund")) {
      if (deployerBal < total + 60_000_000n) throw new Error("deployer does not have enough SOL to fund the test wallets and still pay setup rent");
      for (const s of shortfalls) {
        const sig = (await signAndSend(chain, [SystemProgram.transfer({ fromPubkey: deployer.publicKey, toPubkey: s.key, lamports: s.need })], [deployer])).signature;
        log(`  funded ${s.name} with ${Number(s.need) / 1e9} SOL: ${explorer(sig)}`);
      }
    } else {
      log(`\nTotal ${Number(total) / 1e9} SOL needed. Re-run with --fund to move it from the deployer, or fund the addresses above yourself. Nothing was sent.`);
      process.exit(2);
    }
  }
  if (args.has("--plan")) return;

  // ---- 2. test mints, tokens, config
  for (const [label, mintKp] of [["USDC (test)", usdcT], ["SKR (test)", skrT]] as [string, Keypair][]) {
    if (!(await chain.getAccount(mintKp.publicKey.toBase58()))) {
      const rent = await chain.minimumBalanceForRentExemption(82);
      const r = await signAndSend(chain, ixsCreateMint(deployer.publicKey, mintKp.publicKey, deployer.publicKey, 6, rent), [deployer, mintKp]);
      log(`created ${label} mint ${mintKp.publicKey.toBase58()}: ${explorer(r.signature)}`);
    }
  }
  for (const [name, kp] of [["alice", alice], ["bob", bob]] as [string, Keypair][]) {
    const ata = ataAddress(kp.publicKey, usdcT.publicKey);
    await signAndSend(chain, [ixCreateAtaIdempotent(deployer.publicKey, kp.publicKey, usdcT.publicKey)], [deployer]);
    const bal = tokenAmount((await chain.getAccount(ata.toBase58()))!.data);
    if (bal < 60n * UNIT) {
      const r = await signAndSend(chain, [ixMintTo(usdcT.publicKey, ata, deployer.publicKey, 100n * UNIT, 6)], [deployer]);
      log(`minted 100 test USDC to ${name}: ${explorer(r.signature)}`);
    }
  }
  if (!(await chain.getAccount(program.configPda().toBase58()))) {
    const params = {
      oracle: oracle.publicKey.toBase58(),
      treasury: treasury.publicKey.toBase58(),
      fee_bps: 0,
      max_stake: 100n * UNIT,
      settle_grace_secs: 7_200n,
      allowed_mints: [DEVNET_USDC, usdcT.publicKey.toBase58(), skrT.publicKey.toBase58()],
    };
    log(`\ninit_config (irreversible for this program id): oracle ${params.oracle}, treasury ${params.treasury}, fee 0, max stake 100 tokens, settle grace 2h, mints [devnet USDC, USDC (test), SKR (test)]`);
    const r = await signAndSend(chain, [program.ixInitConfig(deployer.publicKey, params)], [deployer]);
    log(`config initialised: ${explorer(r.signature)}`);
  } else {
    const cfg = program.decodeConfig((await chain.getAccount(program.configPda().toBase58()))!.data);
    if (cfg.oracle !== oracle.publicKey.toBase58()) throw new Error("onchain config has a different oracle than the key in .devnet/oracle.json");
    log("config already initialised on devnet");
  }

  // ---- 3. the backend, in-process, against devnet
  const jwtPath = `${DIR}jwt-secret.txt`;
  if (!existsSync(jwtPath)) writeFileSync(jwtPath, randomBytes(32).toString("hex"));
  const config = loadConfig({
    JWT_SECRET: readFileSync(jwtPath, "utf8"),
    ORACLE_SECRET_KEY: JSON.stringify(Array.from(oracle.secretKey)),
    CRANK_SECRET_KEY: JSON.stringify(Array.from(crank.secretKey)),
    DATABASE_PATH: `${DIR}gate.sqlite`,
    RPC_URL,
    NETWORK: "devnet",
  });
  const s = createServices(config);
  const app = await buildApp(s);
  const api = async (method: "GET" | "POST", url: string, who: { headers: Record<string, string> } | null, payload?: unknown) => {
    const res = await app.inject({ method, url, headers: who?.headers ?? {}, payload: payload as object });
    if (res.statusCode !== 200) throw new Error(`${method} ${url} -> ${res.statusCode} ${res.body}`);
    return res.json();
  };
  const login = async (kp: Keypair) => {
    const wallet = kp.publicKey.toBase58();
    const n = await api("POST", "/v1/auth/nonce", null, { wallet });
    const msg = new TextEncoder().encode(buildSiwsMessage({ domain: n.domain, address: wallet, statement: n.statement, uri: n.uri, nonce: n.nonce, issuedAt: n.issuedAt, expirationTime: n.expirationTime }));
    const v = await api("POST", "/v1/auth/verify", null, { wallet, message: Buffer.from(msg).toString("base64"), signature: Buffer.from(nacl.sign.detached(msg, kp.secretKey)).toString("base64") });
    return { headers: { authorization: `Bearer ${v.token}` } };
  };
  const sendTx = async (kp: Keypair, b64: string, who: { headers: Record<string, string> }) => {
    const tx = Transaction.from(Buffer.from(b64, "base64"));
    tx.partialSign(kp);
    const res = await chain.sendAndConfirm(tx);
    await api("POST", "/v1/challenges/sync", who, { signature: res.signature });
    return res.signature;
  };
  const aliceAuth = await login(alice);
  const bobAuth = await login(bob);
  const devices = { alice: loadDevice("alice"), bob: loadDevice("bob") };
  const registerDevice = async (kp: Keypair, auth: { headers: Record<string, string> }, d: { id: string; spki: Buffer }) => {
    const { challenge } = await api("POST", "/v1/devices/challenge", auth);
    const msg = new TextEncoder().encode(deviceRegistrationMessage(kp.publicKey.toBase58(), d.id, challenge));
    return api("POST", "/v1/devices/register", auth, { devicePublicKey: d.spki.toString("base64"), challenge, walletSignature: Buffer.from(nacl.sign.detached(msg, kp.secretKey)).toString("base64") });
  };
  await registerDevice(alice, aliceAuth, devices.alice);
  await registerDevice(bob, bobAuth, devices.bob);

  const statePath = `${DIR}gate-state.json`;
  type State = { poolA: string; poolB: string; startTs: number; settleAfter: number; before: { alice: string; bob: string }; stage: "A" | "done"; squad?: string };
  const state: State | null = existsSync(statePath) ? (JSON.parse(readFileSync(statePath, "utf8")) as State) : null;
  const bal = async (k: PublicKey) => {
    const a = await chain.getAccount(ataAddress(k, usdcT.publicKey).toBase58());
    return a ? tokenAmount(a.data) : 0n;
  };

  if (!state) return stageA();
  if (state.stage === "done") {
    log("\nGate already completed. Delete backend/.devnet/gate-state.json to run a fresh pool.");
    return;
  }
  return stageB(state);

  // ------------------------------------------------------------------ stage A
  async function stageA() {
    log("\n== Stage A: create pools, join, prove, record check-ins on devnet ==");
    const plan: GoalPlan = {
      title: "20 squats for one day",
      category: "fitness",
      cadence: { periodDays: 1, totalDays: 1, requiredDays: 1 },
      target: { metric: "squats", value: 20, unit: "reps", direction: "atLeast" },
      proofMethods: [{ type: "CAMERA_POSE", params: {}, trustTier: "high" }],
      window: null,
      difficulty: 2,
      verifiable: true,
      unverifiableReason: null,
      suggestedAlternative: null,
      clarifyingQuestions: [],
    };
    const before = { alice: (await bal(alice.publicKey)).toString(), bob: (await bal(bob.publicKey)).toString() };
    const startTs = chain.nowSec() + 240;
    const mint = usdcT.publicKey.toBase58();
    const create = async (kp: Keypair, auth: { headers: Record<string, string> }) => {
      const r = await api("POST", "/v1/challenges/tx/create", auth, { mint, kind: "Squad", mode: "Hard", startTs, plan, maxParticipants: 10 });
      const sig = await sendTx(kp, r.transaction, auth);
      log(`pool ${r.pool} created: ${explorer(sig)}`);
      return r.pool as string;
    };
    const join = async (kp: Keypair, auth: { headers: Record<string, string> }, d: { id: string }, pool: string, stake: bigint) => {
      const r = await api("POST", "/v1/challenges/tx/join", auth, { pool, stake: stake.toString(), tzOffsetMinutes: 0, deviceId: d.id });
      log(`  ${kp.publicKey.toBase58().slice(0, 6)} joined ${pool.slice(0, 6)} with ${Number(stake) / 1e6} test USDC: ${explorer(await sendTx(kp, r.transaction, auth))}`);
    };
    const poolA = await create(alice, aliceAuth);
    const squad = await api("POST", "/v1/squads", aliceAuth, { name: "Gate squad" });
    await api("POST", "/v1/squads/join", bobAuth, { code: squad.inviteCode });
    await api("POST", `/v1/squads/${squad.id}/challenges`, aliceAuth, { pool: poolA });
    await join(alice, aliceAuth, devices.alice, poolA, 10n * UNIT);
    await join(bob, bobAuth, devices.bob, poolA, 10n * UNIT);
    // pool B: bob alone, never proves, so everything is forfeited and swept to the treasury later
    const poolB = await create(bob, bobAuth);
    await join(bob, bobAuth, devices.bob, poolB, 5n * UNIT);

    const wait = startTs + 20 - chain.nowSec();
    if (wait > 0) {
      log(`waiting ${wait}s for the pools to start...`);
      await new Promise((r) => setTimeout(r, wait * 1000));
    }
    // alice proves day 0 (device-signed package; the backend verifies, the oracle records it on chain)
    const sess = await api("POST", "/v1/proofs/session", aliceAuth, { pool: poolA, dayIndex: 0, proofType: "CAMERA_POSE" });
    const now = chain.nowSec();
    const pkg = {
      sessionId: sess.sessionId, nonce: sess.nonce, challengeId: poolA, dayIndex: 0, proofType: "CAMERA_POSE" as const,
      metrics: { reps: 24, livenessPassed: true }, startedAt: now - 60, endedAt: now - 5,
      evidenceHash: createHash("sha256").update(`gate-evidence-${poolA}`).digest("hex"), deviceKeyId: devices.alice.id,
    };
    const signature = cryptoSign("sha256", canonicalPackageBytes(pkg), { key: devices.alice.priv, dsaEncoding: "der" }).toString("base64");
    const sub = await api("POST", "/v1/proofs/submit", aliceAuth, { ...pkg, signature });
    log(`alice's proof accepted (trust tier ${sub.trustTier}); check-in ${sub.checkin.status}${sub.checkin.signature ? `: ${explorer(sub.checkin.signature)}` : ""}`);
    if (sub.checkin.status !== "confirmed" || sub.daysCompleted !== 1) throw new Error("check-in was not recorded on chain");

    const chA = (await api("GET", `/v1/challenges/${poolA}`, aliceAuth)).challenge;
    const next: State = { poolA, poolB, startTs, settleAfter: chA.settleAfterTs, before, stage: "A", squad: squad.id };
    writeFileSync(statePath, JSON.stringify(next, null, 2));
    const when = new Date(chA.settleAfterTs * 1000).toISOString();
    log(`\nStage A complete. Settlement opens at ${when}. Run \`npm run gate:devnet\` again after that to settle, claim, sweep and verify.`);
    process.exit(3);
  }

  // ------------------------------------------------------------------ stage B
  async function stageB(st: State) {
    log("\n== Stage B: settle, claim, sweep, verify ==");
    const now = chain.nowSec();
    if (now < st.settleAfter) {
      const mins = Math.ceil((st.settleAfter - now) / 60);
      log(`Not yet: settlement opens at ${new Date(st.settleAfter * 1000).toISOString()} (in about ${mins} minutes, ${(mins / 60).toFixed(1)} h).`);
      process.exit(3);
    }
    await pollOnce(s);
    const rep = await runCrank(s);
    log(`crank: settled ${rep.settled}, swept ${rep.swept}${rep.errors.length ? `, errors: ${rep.errors.join("; ")}` : ""}`);
    if (rep.errors.length) throw new Error("crank reported errors");
    const chA = (await api("GET", `/v1/challenges/${st.poolA}`, aliceAuth)).challenge;
    if (chA.status !== "Settled") throw new Error(`pool A is ${chA.status}, expected Settled`);

    const c = await api("POST", "/v1/challenges/tx/claim", aliceAuth, { pool: st.poolA });
    log(`alice claims ${Number(c.summary.expectedAmount) / 1e6} test USDC: ${explorer(await sendTx(alice, c.transaction, aliceAuth))}`);
    const bobClaim = await app.inject({ method: "POST", url: "/v1/challenges/tx/claim", headers: bobAuth.headers, payload: { pool: st.poolA } });
    if (bobClaim.json().error?.code !== "nothing_to_claim") throw new Error(`bob should have nothing to claim, got ${bobClaim.body}`);
    await runCrank(s);

    // verify balances against what we recorded before the pools were created
    const a = await bal(alice.publicKey);
    const b = await bal(bob.publicKey);
    const t = await chain.getAccount(ataAddress(treasury.publicKey, usdcT.publicKey).toBase58());
    const treasuryBal = t ? tokenAmount(t.data) : 0n;
    const expectAlice = BigInt(st.before.alice) - 10n * UNIT + 20n * UNIT;
    const expectBob = BigInt(st.before.bob) - 10n * UNIT - 5n * UNIT;
    const checks: [string, bigint, bigint][] = [["alice", a, expectAlice], ["bob", b, expectBob], ["treasury", treasuryBal, 5n * UNIT]];
    for (const [name, got, want] of checks) log(`  ${name}: ${Number(got) / 1e6} (expected ${Number(want) / 1e6}) ${got === want ? "OK" : "MISMATCH"}`);
    for (const pool of [st.poolA, st.poolB]) {
      const v = await chain.getAccount(program.vaultPda(pool).toBase58());
      if (tokenAmount(v!.data) !== 0n) throw new Error(`vault of ${pool} is not empty`);
    }
    if (checks.some(([, g, w]) => g !== w)) throw new Error("balances do not match expectations");
    writeFileSync(statePath, JSON.stringify({ ...st, stage: "done" }, null, 2));
    log("\nGATE PASSED on devnet: create, join, prove, oracle check-in, settle, claim and treasury sweep all verified.");
  }
}

main().then(
  () => process.exit(0),
  (e) => {
    console.error(e instanceof Error ? e.message : e);
    process.exit(1);
  },
);

