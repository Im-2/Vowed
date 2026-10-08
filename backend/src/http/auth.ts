import { randomBytes } from "node:crypto";
import { PublicKey } from "@solana/web3.js";
import type { FastifyInstance, FastifyRequest } from "fastify";
import { jwtVerify, SignJWT } from "jose";
import nacl from "tweetnacl";
import { z } from "zod";
import type { ZodTypeProvider } from "fastify-type-provider-zod";
import { badRequest, unauthorized } from "../errors.js";
import type { Services } from "../services.js";
import { enforce } from "./ratelimit.js";

declare module "fastify" {
  interface FastifyRequest {
    wallet?: string;
    /** when the wallet last signed in (unix seconds); refreshing keeps this, so a session cannot be extended for ever */
    sessionStart?: number;
  }
}

export const NONCE_TTL_SEC = 300;
/** one token lasts 6 hours; /v1/auth/refresh swaps a still-valid token for a new one, up to SESSION_MAX_SEC after the wallet signed in */
export const TOKEN_TTL_SEC = 21_600;
export const SESSION_MAX_SEC = 7 * 86_400;
const STATEMENT = "Sign in to Vowed. This does not move any funds.";

export const pubkeySchema = z.string().refine((v) => {
  try {
    return new PublicKey(v).toBase58() === v;
  } catch {
    return false;
  }
}, "invalid public key");

export function decodeB64(value: string, maxBytes: number): Uint8Array {
  const buf = Buffer.from(value, "base64");
  if (buf.length === 0 || buf.length > maxBytes) throw badRequest("bad_encoding", "invalid base64 payload");
  return buf;
}

export interface SiwsFields {
  domain: string;
  address: string;
  nonce?: string;
  expirationTime?: string;
}

/** Parses the text of a Sign-In With Solana message (the wallet builds it from our payload). */
export function parseSiws(message: string): SiwsFields | null {
  const lines = message.split("\n");
  const first = /^(\S+) wants you to sign in with your Solana account:$/.exec(lines[0] ?? "");
  if (!first || !lines[1]) return null;
  const field = (name: string) => {
    const l = lines.find((x) => x.startsWith(`${name}: `));
    return l ? l.slice(name.length + 2).trim() : undefined;
  };
  return { domain: first[1]!, address: lines[1].trim(), nonce: field("Nonce"), expirationTime: field("Expiration Time") };
}

export async function issueToken(s: Services, wallet: string, sessionStart?: number): Promise<{ token: string; expiresAt: number }> {
  const expiresAt = s.wallNow() + TOKEN_TTL_SEC;
  const token = await new SignJWT({ sat: sessionStart ?? s.wallNow() })
    .setProtectedHeader({ alg: "HS256" })
    .setSubject(wallet)
    .setIssuer("vowed-backend")
    .setIssuedAt(s.wallNow())
    .setExpirationTime(expiresAt)
    .sign(new TextEncoder().encode(s.config.JWT_SECRET));
  return { token, expiresAt };
}

export function authenticate(s: Services) {
  const key = new TextEncoder().encode(s.config.JWT_SECRET);
  return async (req: FastifyRequest) => {
    const h = req.headers.authorization;
    if (!h?.startsWith("Bearer ")) throw unauthorized();
    try {
      const { payload } = await jwtVerify(h.slice(7), key, { issuer: "vowed-backend", algorithms: ["HS256"] });
      if (!payload.sub) throw unauthorized();
      req.wallet = payload.sub;
      req.sessionStart = typeof payload.sat === "number" ? payload.sat : payload.iat;
    } catch {
      throw unauthorized("invalid or expired token");
    }
    enforce(s, `wallet:${req.wallet}`, 240, 60);
  };
}

export function registerAuthRoutes(app: FastifyInstance, s: Services) {
  const r = app.withTypeProvider<ZodTypeProvider>();

  r.post(
    "/v1/auth/nonce",
    {
      schema: {
        tags: ["auth"],
        summary: "Get a sign-in nonce and the exact payload to pass to the wallet's sign-in",
        body: z.object({ wallet: pubkeySchema }),
        response: {
          200: z.object({
            nonce: z.string(),
            domain: z.string(),
            statement: z.string(),
            uri: z.string(),
            issuedAt: z.string(),
            expirationTime: z.string(),
          }),
        },
      },
    },
    async (req) => {
      enforce(s, `ip:${req.ip}:nonce`, 30, 60);
      const nonce = randomBytes(16).toString("hex");
      const exp = s.wallNow() + NONCE_TTL_SEC;
      s.db.prepare("INSERT INTO nonces (nonce, purpose, wallet, expires_at) VALUES (?, 'auth', ?, ?)").run(nonce, req.body.wallet, exp);
      return {
        nonce,
        domain: s.config.AUTH_DOMAIN,
        statement: STATEMENT,
        uri: `https://${s.config.AUTH_DOMAIN}`,
        issuedAt: new Date(s.wallNow() * 1000).toISOString(),
        expirationTime: new Date(exp * 1000).toISOString(),
      };
    },
  );

  r.post(
    "/v1/auth/verify",
    {
      schema: {
        tags: ["auth"],
        summary: "Exchange the wallet's signed sign-in message for a short-lived token",
        body: z.object({
          wallet: pubkeySchema,
          /** base64 of the exact message bytes the wallet signed */
          message: z.string().max(4096),
          /** base64 ed25519 signature (64 bytes) */
          signature: z.string().max(200),
        }),
        response: { 200: z.object({ token: z.string(), expiresAt: z.number(), wallet: z.string() }) },
      },
    },
    async (req) => {
      enforce(s, `ip:${req.ip}:verify`, 30, 60);
      const { wallet } = req.body;
      const msgBytes = decodeB64(req.body.message, 2048);
      const sig = decodeB64(req.body.signature, 64);
      if (sig.length !== 64) throw badRequest("bad_signature", "signature must be 64 bytes");
      if (!nacl.sign.detached.verify(msgBytes, sig, new PublicKey(wallet).toBytes())) throw unauthorized("signature does not match");

      const fields = parseSiws(new TextDecoder().decode(msgBytes));
      if (!fields || fields.address !== wallet) throw unauthorized("message does not match the wallet");
      if (fields.domain !== s.config.AUTH_DOMAIN) throw unauthorized("wrong domain");
      if (!fields.nonce) throw unauthorized("message has no nonce");
      if (fields.expirationTime && Date.parse(fields.expirationTime) < s.wallNow() * 1000) throw unauthorized("message expired");

      const used = s.db
        .prepare(
          "UPDATE nonces SET used_at = ? WHERE nonce = ? AND purpose = 'auth' AND wallet = ? AND used_at IS NULL AND expires_at > ?",
        )
        .run(s.wallNow(), fields.nonce, wallet, s.wallNow());
      if (Number(used.changes) !== 1) throw unauthorized("nonce unknown, used or expired");

      s.db.prepare("INSERT OR IGNORE INTO users (wallet, created_at) VALUES (?, ?)").run(wallet, s.wallNow());
      const { token, expiresAt } = await issueToken(s, wallet);
      return { token, expiresAt, wallet };
    },
  );

  r.post(
    "/v1/auth/refresh",
    {
      preHandler: authenticate(s),
      schema: {
        tags: ["auth"],
        summary: "Swap a still-valid token for a new one without asking the wallet again. Refused once the session is older than 7 days since the wallet signed in",
        response: { 200: z.object({ token: z.string(), expiresAt: z.number(), wallet: z.string() }) },
      },
    },
    async (req) => {
      enforce(s, `wallet:${req.wallet}:refresh`, 30, 3_600);
      if (s.wallNow() - (req.sessionStart ?? 0) > SESSION_MAX_SEC) throw unauthorized("the session is too old; sign in with the wallet again");
      const { token, expiresAt } = await issueToken(s, req.wallet!, req.sessionStart);
      return { token, expiresAt, wallet: req.wallet! };
    },
  );
}

/** Builds the SIWS message text exactly as a wallet would (used by tests and the dev CLI). */
export function buildSiwsMessage(p: { domain: string; address: string; statement: string; uri: string; nonce: string; issuedAt: string; expirationTime: string }): string {
  return `${p.domain} wants you to sign in with your Solana account:\n${p.address}\n\n${p.statement}\n\nURI: ${p.uri}\nVersion: 1\nNonce: ${p.nonce}\nIssued At: ${p.issuedAt}\nExpiration Time: ${p.expirationTime}`;
}
