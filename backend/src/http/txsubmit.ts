import { createHash } from "node:crypto";
import { PublicKey, Transaction } from "@solana/web3.js";
import bs58 from "bs58";
import type { FastifyInstance } from "fastify";
import type { ZodTypeProvider } from "fastify-type-provider-zod";
import nacl from "tweetnacl";
import { z } from "zod";
import { ChainError } from "../chain/types.js";
import { explainSimulation, simulateOrThrow } from "../chain/simulate.js";
import { getChallenge, getParticipant, syncParticipation, syncPool } from "../challenges/sync.js";
import { ApiError, badRequest, conflict, notFound } from "../errors.js";
import type { Services } from "../services.js";
import { authenticate, decodeB64, pubkeySchema } from "./auth.js";
import { enforce } from "./ratelimit.js";

/**
 * The wallet-signs-only path and the "did it land?" check.
 *
 * Some wallet apps never answer `sign_and_send_transactions`. For them Vowed asks the wallet to only SIGN (`sign_transactions`) and hands the signed
 * transaction to this server, which sends it. To keep that safe the server sends ONLY a transaction it built itself, for this very wallet (it remembers
 * the hash of every message it built), checks the wallet's signature, simulates it again, and sends it at most once: asking again returns the same signature.
 */
export type BuiltKind = "create" | "join" | "claim";

const hashOf = (messageBytes: Uint8Array) => createHash("sha256").update(messageBytes).digest("hex");

/** Remembers a transaction this server built, so that its signed copy can be submitted later. */
export function recordBuilt(s: Services, wallet: string, kind: BuiltKind, pool: string, tx: Transaction): string {
  const h = hashOf(tx.serializeMessage());
  s.db.prepare("INSERT OR IGNORE INTO built_txs (message_hash, wallet, kind, pool, created_at, status) VALUES (?,?,?,?,?, 'built')").run(h, wallet, kind, pool, s.wallNow());
  return h;
}

interface BuiltRow {
  message_hash: string;
  wallet: string;
  kind: string;
  pool: string;
  status: string;
  signature: string | null;
}

export async function submitSigned(s: Services, wallet: string, signedBase64: string): Promise<{ signature: string; alreadySubmitted: boolean }> {
  const raw = decodeB64(signedBase64, 4_000);
  let tx: Transaction;
  try {
    tx = Transaction.from(raw);
  } catch {
    throw badRequest("bad_transaction", "that is not a transaction");
  }
  const hash = hashOf(tx.serializeMessage());
  const row = s.db.prepare("SELECT message_hash, wallet, kind, pool, status, signature FROM built_txs WHERE message_hash = ?").get(hash) as BuiltRow | undefined;
  if (!row || row.wallet !== wallet) throw notFound("transaction");
  if (!tx.feePayer || tx.feePayer.toBase58() !== wallet) throw badRequest("bad_fee_payer", "the fee payer must be your wallet");
  const sig = tx.signatures.find((x) => x.publicKey.toBase58() === wallet)?.signature;
  if (!sig || !nacl.sign.detached.verify(tx.serializeMessage(), sig, new PublicKey(wallet).toBytes())) throw badRequest("bad_signature", "your wallet's signature is missing or does not match this transaction");
  const signature = bs58.encode(sig);

  if (row.status === "sent") return { signature: row.signature ?? signature, alreadySubmitted: true };
  // take the right to send it: only one request can move 'built' (or a failed try) to 'sending'
  const taken = s.db.prepare("UPDATE built_txs SET status = 'sending', signature = ? WHERE message_hash = ? AND status IN ('built','failed')").run(signature, hash);
  if (Number(taken.changes) !== 1) {
    const again = s.db.prepare("SELECT status, signature FROM built_txs WHERE message_hash = ?").get(hash) as { status: string; signature: string | null };
    if (again.status === "sent") return { signature: again.signature ?? signature, alreadySubmitted: true };
    throw conflict("in_progress", "this transaction is being sent right now; ask again in a moment");
  }
  try {
    await simulateOrThrow(s.chain, tx);
    await s.chain.sendAndConfirm(tx);
  } catch (e) {
    const text = e instanceof Error ? e.message : String(e);
    // a transaction that was already processed is a success for the person (an earlier send, or a wallet that also sent it)
    if (/already (been )?processed/i.test(text)) {
      s.db.prepare("UPDATE built_txs SET status = 'sent' WHERE message_hash = ?").run(hash);
      return { signature, alreadySubmitted: true };
    }
    s.db.prepare("UPDATE built_txs SET status = 'failed', error = ? WHERE message_hash = ?").run(text.slice(0, 200), hash);
    if (e instanceof ApiError) throw e;
    if (e instanceof ChainError) {
      if (/blockhash/i.test(text)) throw new ApiError(409, "blockhash_expired", "This transaction expired before it was sent. Go back and try again.");
      const f = explainSimulation(text, e.logs ?? []);
      throw new ApiError(422, f.code, f.message, { error: text.slice(0, 300) });
    }
    throw e;
  }
  s.db.prepare("UPDATE built_txs SET status = 'sent' WHERE message_hash = ?").run(hash);
  return { signature, alreadySubmitted: false };
}

/** Did the step the wallet was asked for land on chain? Reads the chain, not the wallet. */
export async function checkLanded(s: Services, wallet: string, kind: BuiltKind, pool: string) {
  const challengeSeen = Boolean(await syncPool(s, pool));
  await syncParticipation(s, pool, wallet);
  const me = getParticipant(s, pool, wallet);
  const c = getChallenge(s, pool);
  let landed = false;
  let detail = "";
  if (kind === "create") {
    landed = challengeSeen && Boolean(me);
    detail = challengeSeen ? (me ? "the challenge exists and your stake is in it" : "the challenge exists but your stake is not in it") : "the challenge does not exist on chain";
  } else if (kind === "join") {
    landed = Boolean(me);
    detail = me ? "your stake is in the challenge" : "your stake is not in the challenge";
  } else {
    landed = me?.status === "Claimed";
    detail = landed ? "the payout was claimed" : "the payout is not claimed yet";
  }
  let signature: string | null = null;
  if (landed || challengeSeen) {
    try {
      const sigs = await s.chain.getSignaturesForAddress(pool, { limit: 1 });
      signature = sigs[0]?.signature ?? null;
    } catch {
      signature = null;
    }
  }
  return { landed, detail, signature, status: c?.status ?? null };
}

export function registerTxSubmitRoutes(app: FastifyInstance, s: Services) {
  const r = app.withTypeProvider<ZodTypeProvider>();
  const auth = authenticate(s);

  r.post(
    "/v1/tx/submit",
    {
      preHandler: auth,
      schema: {
        tags: ["challenges"],
        summary: "Send a transaction the wallet only SIGNED (sign_transactions). Only a transaction this server built for this wallet is accepted; it is simulated again and sent at most once, and asking again returns the same signature",
        body: z.object({ transaction: z.string().max(6_000) }),
        response: { 200: z.object({ signature: z.string(), alreadySubmitted: z.boolean() }) },
      },
    },
    async (req) => {
      enforce(s, `wallet:${req.wallet}:submit`, 30, 60);
      return submitSigned(s, req.wallet!, req.body.transaction);
    },
  );

  r.post(
    "/v1/challenges/check",
    {
      preHandler: auth,
      schema: {
        tags: ["challenges"],
        summary: "Did the create / join / claim the wallet was asked for land on chain? Used after a wallet closed or never answered, before another signature is requested",
        body: z.object({ pool: pubkeySchema, kind: z.enum(["create", "join", "claim"]) }),
        response: { 200: z.object({ landed: z.boolean(), detail: z.string(), signature: z.string().nullable(), status: z.string().nullable() }) },
      },
    },
    async (req) => {
      enforce(s, `wallet:${req.wallet}:landcheck`, 60, 60);
      return checkLanded(s, req.wallet!, req.body.kind, req.body.pool);
    },
  );
}
