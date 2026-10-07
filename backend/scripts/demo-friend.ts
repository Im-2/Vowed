/**
 * DEMO TOOL (devnet only): plays "a friend" in a pool that the app created, so the demo has a second person.
 * Uses the throwaway test wallet bob (backend/.devnet/bob.json) and a software device key, and talks to a running backend over HTTP.
 *
 *   npx tsx scripts/demo-friend.ts <pool> [--stake 1] [--prove 0,1] [--url http://127.0.0.1:8787]
 *
 * Without --prove the friend never checks in, so their stake (or the penalty share, in Soft mode) is forfeited to the winner.
 * Test tokens only. Never touches a faucet.
 */
import { createHash, createPrivateKey, createPublicKey, generateKeyPairSync, sign as cryptoSign, type KeyObject } from "node:crypto";
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { Keypair, Transaction } from "@solana/web3.js";
import nacl from "tweetnacl";
import { Web3Chain } from "../src/chain/web3chain.js";
import { buildSiwsMessage } from "../src/http/auth.js";
import { deviceRegistrationMessage } from "../src/http/devices.js";
import { canonicalPackageBytes } from "../src/proofs/verify.js";

const DIR = new URL("../.devnet/", import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, "$1");
const argv = process.argv.slice(2);
const pool = argv.find((a) => !a.startsWith("--") && argv[argv.indexOf(a) - 1]?.startsWith("--") !== true);
const opt = (name: string, dflt: string) => {
  const i = argv.indexOf(`--${name}`);
  return i >= 0 && argv[i + 1] ? argv[i + 1]! : dflt;
};
if (!pool) {
  console.error("usage: demo-friend.ts <pool> [--stake 1] [--prove 0,1] [--url http://127.0.0.1:8787]");
  process.exit(1);
}
const base = opt("url", "http://127.0.0.1:8787");
const stake = BigInt(Math.round(Number(opt("stake", "1")) * 1e6));
const proveDays = opt("prove", "").split(",").filter(Boolean).map(Number);
const RPC = process.env.RPC_URL ?? "https://api.devnet.solana.com";

const bob = Keypair.fromSecretKey(Uint8Array.from(JSON.parse(readFileSync(`${DIR}bob.json`, "utf8")) as number[]));
function device(): { id: string; spki: Buffer; priv: KeyObject } {
  const path = `${DIR}bob-device.pem`;
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

async function api(method: "GET" | "POST", path: string, token: string | null, body?: unknown) {
  const res = await fetch(base + path, { method, headers: { "content-type": "application/json", ...(token ? { authorization: `Bearer ${token}` } : {}) }, body: body ? JSON.stringify(body) : undefined });
  const text = await res.text();
  if (!res.ok) throw new Error(`${method} ${path} -> ${res.status} ${text}`);
  return JSON.parse(text);
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

async function main() {
  const wallet = bob.publicKey.toBase58();
  const n = await api("POST", "/v1/auth/nonce", null, { wallet });
  const msg = new TextEncoder().encode(buildSiwsMessage({ domain: n.domain, address: wallet, statement: n.statement, uri: n.uri, nonce: n.nonce, issuedAt: n.issuedAt, expirationTime: n.expirationTime }));
  const v = await api("POST", "/v1/auth/verify", null, { wallet, message: Buffer.from(msg).toString("base64"), signature: Buffer.from(nacl.sign.detached(msg, bob.secretKey)).toString("base64") });
  const token: string = v.token;
  const dev = device();
  const { challenge } = await api("POST", "/v1/devices/challenge", token, {});
  const reg = new TextEncoder().encode(deviceRegistrationMessage(wallet, dev.id, challenge));
  await api("POST", "/v1/devices/register", token, { devicePublicKey: dev.spki.toString("base64"), challenge, walletSignature: Buffer.from(nacl.sign.detached(reg, bob.secretKey)).toString("base64") });

  const chain = new Web3Chain(RPC);
  const before = await api("GET", `/v1/challenges/${pool}`, token);
  const c = before.challenge;
  console.log(`friend (bob ${wallet.slice(0, 6)}...) joins ${c.isDemo ? "DEMO pool" : "pool"} ${pool!.slice(0, 6)}... "${c.plan?.title}" with ${Number(stake) / 1e6} test USDC`);
  const j = await api("POST", "/v1/challenges/tx/join", token, { pool, stake: stake.toString(), tzOffsetMinutes: 0, deviceId: dev.id });
  const tx = Transaction.from(Buffer.from(j.transaction, "base64"));
  tx.partialSign(bob);
  const sent = await chain.sendAndConfirm(tx);
  await api("POST", "/v1/challenges/sync", token, { signature: sent.signature });
  console.log(`joined: https://explorer.solana.com/tx/${sent.signature}?cluster=devnet`);

  for (const day of proveDays) {
    const plan = c.plan;
    const type: string = plan.proofMethods[0].type;
    const t = plan.target as { value: number; unit: string };
    const sec = ({ s: 1, seconds: 1, minutes: 60, min: 60, hours: 3600 } as Record<string, number>)[t.unit] ?? 1;
    const start = c.startTs + day * c.daySecs;
    while (chain.nowSec() < start + 1) await sleep(1000);
    const sess = await api("POST", "/v1/proofs/session", token, { pool, dayIndex: day, proofType: type });
    const now = chain.nowSec();
    const metrics: Record<string, unknown> =
      type === "SELF_ATTEST" ? { done: true }
      : type === "STEPS" ? { steps: Math.ceil(t.value) + 10 }
      : type === "FOCUS_TIMER" ? { focusedSeconds: Math.ceil(t.value * sec) }
      : type === "GEOFENCE" ? { inside: true, dwellSeconds: Math.ceil(t.value * sec) }
      : type === "USAGE_LIMIT" ? { usageSeconds: 0, packagesChecked: ["friend.sim"] }
      : type === "NO_USE_WINDOW" ? { usageSecondsInWindow: 0, packagesChecked: ["friend.sim"] }
      : { reps: Math.ceil(t.value) + 2, livenessPassed: true };
    const dwell = Number((metrics.focusedSeconds as number | undefined) ?? (metrics.dwellSeconds as number | undefined) ?? 5);
    const pkg = {
      sessionId: sess.sessionId, nonce: sess.nonce, challengeId: pool, dayIndex: day, proofType: type as never, metrics,
      startedAt: now - Math.max(10, dwell + 2, Number((metrics.reps as number | undefined) ?? 0) * 2), endedAt: now - 1,
      evidenceHash: createHash("sha256").update(`friend-sim-${pool}-${day}`).digest("hex"), deviceKeyId: dev.id,
    };
    const signature = cryptoSign("sha256", canonicalPackageBytes(pkg), { key: dev.priv, dsaEncoding: "der" }).toString("base64");
    const sub = await api("POST", "/v1/proofs/submit", token, { ...pkg, signature });
    console.log(`friend's day-${day} proof accepted (${sub.trustTier}); check-in ${sub.checkin.status}`);
  }
}

main().then(() => process.exit(0), (e) => { console.error(e instanceof Error ? e.message : e); process.exit(1); });
