/**
 * Read-only probe of the hosted rewards endpoints with the throwaway wallet bob. Prints results only; no keys, no tokens.
 *   npx tsx scripts/rewards-probe.ts https://vowed-backend.onrender.com
 */
import { readFileSync } from "node:fs";
import { Keypair } from "@solana/web3.js";
import nacl from "tweetnacl";
import { buildSiwsMessage } from "../src/http/auth.js";

const base = (process.argv[2] ?? "").replace(/\/$/, "");
if (!base) throw new Error("usage: hosted-check.ts <base url> [--claim]");
const DIR = new URL("../.devnet/", import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, "$1");
const bob = Keypair.fromSecretKey(Uint8Array.from(JSON.parse(readFileSync(`${DIR}bob.json`, "utf8")) as number[]));
let token = "";
async function api(method: string, path: string, body?: unknown) {
  const res = await fetch(base + path, { method, headers: { "content-type": "application/json", ...(token ? { authorization: `Bearer ${token}` } : {}) }, body: body ? JSON.stringify(body) : undefined });
  const text = await res.text();
  let json: any = null;
  try { json = JSON.parse(text); } catch { /* not json */ }
  return { status: res.status, json, text };
}
const line = (k: string, v: unknown) => console.log(`${k.padEnd(34)} ${typeof v === "string" ? v : JSON.stringify(v)}`);

const t0 = Date.now();
const h = await api("GET", "/v1/health");
line("health", { status: h.status, ...h.json, ms: Date.now() - t0 });
const wallet = bob.publicKey.toBase58();
const n = await api("POST", "/v1/auth/nonce", { wallet });
const msg = new TextEncoder().encode(buildSiwsMessage({ domain: n.json.domain, address: wallet, statement: n.json.statement, uri: n.json.uri, nonce: n.json.nonce, issuedAt: n.json.issuedAt, expirationTime: n.json.expirationTime }));
const v = await api("POST", "/v1/auth/verify", { wallet, message: Buffer.from(msg).toString("base64"), signature: Buffer.from(nacl.sign.detached(msg, bob.secretKey)).toString("base64") });
line("sign-in", v.status === 200 ? "ok" : `failed ${v.status} ${v.text.slice(0, 120)}`);
token = v.json?.token ?? "";
if (!token) process.exit(1);


for (const p of ["/v1/rewards", "/v1/faucet"]) {
  const r = await api("GET", p);
  line(p, r.status === 200 ? r.json : `HTTP ${r.status} ${r.text.slice(0, 300)}`);
}
