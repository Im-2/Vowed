import { createHash, generateKeyPairSync, sign as cryptoSign, type KeyObject } from "node:crypto";
import { readFileSync } from "node:fs";
import { Keypair } from "@solana/web3.js";
import type { FastifyInstance } from "fastify";
import nacl from "tweetnacl";
import type { AccountData, Chain, SendResult, SignatureInfo } from "../../src/chain/types.js";
import { loadConfig } from "../../src/config.js";
import { openDb } from "../../src/db.js";
import { GoogleAttestationVerifier, type AttestationResult, type AttestationVerifier } from "../../src/devices/attestation.js";
import { canonicalJson, type GoalPlan } from "../../src/domain/plan.js";
import { buildApp } from "../../src/http/app.js";
import { buildSiwsMessage } from "../../src/http/auth.js";
import { deviceRegistrationMessage } from "../../src/http/devices.js";
import { VowedProgram } from "../../src/program/client.js";
import { MemoryPushSender } from "../../src/push/types.js";
import { canonicalPackageBytes, type ProofPackage } from "../../src/proofs/verify.js";
import type { Services } from "../../src/services.js";

/** A chain with no program: enough for auth, devices, squads and pure-logic API tests. */
export class StubChain implements Chain {
  accounts = new Map<string, AccountData>();
  time = Math.floor(Date.now() / 1000);
  async getAccount(a: string) {
    return this.accounts.get(a) ?? null;
  }
  async latestBlockhash() {
    return { blockhash: "11111111111111111111111111111111", lastValidBlockHeight: 1 };
  }
  async sendAndConfirm(): Promise<SendResult> {
    throw new Error("StubChain cannot send transactions");
  }
  nowSec() {
    return this.time;
  }
  async minimumBalanceForRentExemption() {
    return 1_000_000;
  }
  async getSignaturesForAddress(): Promise<SignatureInfo[]> {
    return [];
  }
  async getTransactionLogs() {
    return null;
  }
}

export const fakeAttestation = (result?: Partial<AttestationResult>): AttestationVerifier => ({
  async verify() {
    return { ok: true, level: "tee", verifiedBoot: "verified", reason: "verified (test double)", ...result };
  },
});

export interface World {
  s: Services;
  app: FastifyInstance;
  chain: Chain;
  push: MemoryPushSender;
  /** Real-time clock used for sessions, nonces and rate limits. Advance it to expire things. */
  clock: { wall: number };
  oracle: Keypair;
  crank: Keypair;
}

export async function makeWorld(opts: { chain?: Chain; attestation?: AttestationVerifier; requireAttestation?: boolean; jwtSecret?: string; env?: Record<string, string> } = {}): Promise<World> {
  const oracle = Keypair.generate();
  const crank = Keypair.generate();
  const config = loadConfig({
    JWT_SECRET: opts.jwtSecret ?? "test-secret-test-secret-test-secret-123",
    ORACLE_SECRET_KEY: JSON.stringify(Array.from(oracle.secretKey)),
    CRANK_SECRET_KEY: JSON.stringify(Array.from(crank.secretKey)),
    DATABASE_PATH: ":memory:",
    REQUIRE_ATTESTATION: opts.requireAttestation ? "true" : "false",
    NETWORK: "localnet",
    ...opts.env,
  });
  const chain = opts.chain ?? new StubChain();
  const push = new MemoryPushSender();
  const clock = { wall: Math.floor(Date.now() / 1000) };
  const roots = JSON.parse(readFileSync(new URL("../../src/devices/google-roots.json", import.meta.url), "utf8")) as string[];
  const s: Services = {
    config,
    db: openDb(":memory:"),
    chain,
    program: new VowedProgram(),
    oracle,
    crank,
    push,
    attestation: opts.attestation ?? new GoogleAttestationVerifier(roots),
    now: () => chain.nowSec(),
    wallNow: () => clock.wall,
  };
  const app = await buildApp(s);
  return { s, app, chain, push, clock, oracle, crank };
}

export async function signIn(w: World, kp: Keypair): Promise<{ token: string; headers: { authorization: string } }> {
  const wallet = kp.publicKey.toBase58();
  const n = (await w.app.inject({ method: "POST", url: "/v1/auth/nonce", payload: { wallet } })).json();
  const message = buildSiwsMessage({ domain: n.domain, address: wallet, statement: n.statement, uri: n.uri, nonce: n.nonce, issuedAt: n.issuedAt, expirationTime: n.expirationTime });
  const bytes = new TextEncoder().encode(message);
  const signature = nacl.sign.detached(bytes, kp.secretKey);
  const res = await w.app.inject({
    method: "POST",
    url: "/v1/auth/verify",
    payload: { wallet, message: Buffer.from(bytes).toString("base64"), signature: Buffer.from(signature).toString("base64") },
  });
  if (res.statusCode !== 200) throw new Error(`sign-in failed: ${res.body}`);
  const { token } = res.json();
  return { token, headers: { authorization: `Bearer ${token}` } };
}

export interface TestDevice {
  id: string;
  spki: Buffer;
  priv: KeyObject;
  signPackage(pkg: Omit<ProofPackage, "signature">): ProofPackage;
}

export function makeDevice(): TestDevice {
  const { publicKey, privateKey } = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
  const spki = publicKey.export({ type: "spki", format: "der" });
  const id = createHash("sha256").update(spki).digest("hex");
  return {
    id,
    spki,
    priv: privateKey,
    signPackage(pkg) {
      const sig = cryptoSign("sha256", canonicalPackageBytes(pkg), { key: privateKey, dsaEncoding: "der" });
      return { ...pkg, signature: sig.toString("base64") };
    },
  };
}

export async function registerDevice(w: World, kp: Keypair, auth: { headers: { authorization: string } }, device: TestDevice, attestationChain?: string[]) {
  const wallet = kp.publicKey.toBase58();
  const { challenge } = (await w.app.inject({ method: "POST", url: "/v1/devices/challenge", headers: auth.headers })).json();
  const msg = new TextEncoder().encode(deviceRegistrationMessage(wallet, device.id, challenge));
  return w.app.inject({
    method: "POST",
    url: "/v1/devices/register",
    headers: auth.headers,
    payload: {
      devicePublicKey: device.spki.toString("base64"),
      attestationChain,
      challenge,
      walletSignature: Buffer.from(nacl.sign.detached(msg, kp.secretKey)).toString("base64"),
    },
  });
}

export const samplePlan = (over: Partial<GoalPlan> = {}): GoalPlan => ({
  title: "20 squats a day",
  category: "fitness",
  cadence: { periodDays: 1, totalDays: 3, requiredDays: 2 },
  target: { metric: "squats", value: 20, unit: "reps", direction: "atLeast" },
  proofMethods: [{ type: "CAMERA_POSE", params: {}, trustTier: "high" }],
  window: null,
  difficulty: 3,
  verifiable: true,
  unverifiableReason: null,
  suggestedAlternative: null,
  clarifyingQuestions: [],
  ...over,
});

export { canonicalJson };
