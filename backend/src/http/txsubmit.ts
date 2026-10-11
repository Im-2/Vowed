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
import { ApiError, badRequest, conflict } from "../errors.js";
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

const COMPUTE_BUDGET = "ComputeBudget111111111111111111111111111111";
/** the most a wallet may add as a priority fee (SetComputeUnitPrice x SetComputeUnitLimit), in lamports: 0.001 SOL */
export const MAX_PRIORITY_FEE_LAMPORTS = 1_000_000;

/**
 * What a transaction DOES, independent of how a wallet app packed it: the fee payer and every instruction except the Compute Budget ones (program,
 * accounts with their signer/writable flags, data). Wallet apps such as Phantom add Compute Budget instructions (a priority fee) and may refresh the
 * blockhash before signing, so the message bytes differ from what this server built while the staking instructions are identical.
 */
function semanticHash(tx: Transaction): string {
  const m = tx.compileMessage();
  const keys = m.accountKeys;
  const h = createHash("sha256");
  h.update(`payer:${keys[0].toBase58()}\n`);
  for (const ix of m.instructions) {
    const program = keys[ix.programIdIndex].toBase58();
    if (program === COMPUTE_BUDGET) continue;
    const accounts = ix.accounts.map((i) => `${keys[i].toBase58()}:${m.isAccountSigner(i) ? "s" : "-"}${m.isAccountWritable(i) ? "w" : "-"}`).join(",");
    h.update(`${program}|${accounts}|${Buffer.from(bs58.decode(ix.data)).toString("hex")}\n`);
  }
  return h.digest("hex");
}

/** The priority fee the Compute Budget instructions of this transaction would cost, in lamports (0 when it has none). */
function priorityFeeLamports(tx: Transaction): number {
  const m = tx.compileMessage();
  let limit = 200_000;
  let price = 0n;
  for (const ix of m.instructions) {
    if (m.accountKeys[ix.programIdIndex].toBase58() !== COMPUTE_BUDGET) continue;
    const d = Buffer.from(bs58.decode(ix.data));
    if (d[0] === 2 && d.length >= 5) limit = d.readUInt32LE(1);
    if (d[0] === 3 && d.length >= 9) price = d.readBigUInt64LE(1);
  }
  return Number((price * BigInt(limit)) / 1_000_000n);
}

const hashOf = semanticHash;

/** Remembers a transaction this server built, so that its signed copy can be submitted later. */
export function recordBuilt(s: Services, wallet: string, kind: BuiltKind, pool: string, tx: Transaction): string {
  const h = hashOf(tx);
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
  const hash = hashOf(tx);
  const row = s.db.prepare("SELECT message_hash, wallet, kind, pool, status, signature FROM built_txs WHERE message_hash = ?").get(hash) as BuiltRow | undefined;
  if (!row || row.wallet !== wallet) {
    const m = tx.compileMessage();
    const programs = [...new Set(m.instructions.map((ix) => m.accountKeys[ix.programIdIndex].toBase58().slice(0, 6)))];
    throw new ApiError(404, "not_found", `transaction not found: Vowed did not build this one for your wallet (programs in it: ${programs.join(", ")})`);
  }
  const priority = priorityFeeLamports(tx);
  if (priority > MAX_PRIORITY_FEE_LAMPORTS) throw badRequest("fee_too_high", "the wallet added a priority fee that is too high for a test transaction");
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
    // a blockhash the node does not know yet ("Blockhash not found") is common on the public devnet RPC for a blockhash another node (or the wallet's) handed out
    // a few seconds ago: ask again a few times before saying it expired
    for (let attempt = 0; ; attempt++) {
      try {
        await simulateOrThrow(s.chain, tx);
        await s.chain.sendAndConfirm(tx);
        break;
      } catch (e) {
        const text = e instanceof Error ? e.message : String(e);
        if (!/blockhash/i.test(text) || attempt >= 3) throw e;
        await new Promise((r) => setTimeout(r, 1_500));
      }
    }
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
      if (/blockhash/i.test(text)) throw new ApiError(409, "blockhash_expired", `This transaction expired before it was sent. Go back and try again. (the network said: ${text.slice(0, 120)})`, { error: text.slice(0, 300), blockhash: tx.recentBlockhash });
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

/**
 * The same transaction with a fresh blockhash. A wallet app can take over a minute (a cold start, its Connect screen, its approval screen), which is longer than a
 * blockhash lives, so the app asks for a fresh one right before it sends the signing request. Only a transaction this server built for this wallet and that has not been
 * sent is re-issued, and only the blockhash changes (the staking instructions, accounts and fee payer are untouched, so the match by content still holds).
 */
export async function refreshBlockhash(s: Services, wallet: string, unsignedBase64: string): Promise<{ transaction: string }> {
  const raw = decodeB64(unsignedBase64, 4_000);
  let tx: Transaction;
  try {
    tx = Transaction.from(raw);
  } catch {
    throw badRequest("bad_transaction", "that is not a transaction");
  }
  const row = s.db.prepare("SELECT message_hash, wallet, status FROM built_txs WHERE message_hash = ?").get(hashOf(tx)) as { wallet: string; status: string } | undefined;
  if (!row || row.wallet !== wallet) throw new ApiError(404, "not_found", "transaction not found: Vowed did not build this one for your wallet");
  if (row.status === "sent" || row.status === "sending") throw conflict("already_sent", "this transaction was already sent");
  const { blockhash, lastValidBlockHeight } = await s.chain.latestBlockhash();
  tx.recentBlockhash = blockhash;
  tx.lastValidBlockHeight = lastValidBlockHeight;
  tx.signatures = tx.signatures.map((x) => ({ publicKey: x.publicKey, signature: null }));
  return { transaction: tx.serialize({ requireAllSignatures: false, verifySignatures: false }).toString("base64") };
}

export function registerTxSubmitRoutes(app: FastifyInstance, s: Services) {
  const r = app.withTypeProvider<ZodTypeProvider>();
  const auth = authenticate(s);

  r.post(
    "/v1/tx/refresh",
    {
      preHandler: auth,
      schema: {
        tags: ["challenges"],
        summary: "The same transaction the server built, with a fresh blockhash (a wallet app may take longer than a blockhash lives). Only the blockhash changes",
        body: z.object({ transaction: z.string().max(6_000) }),
        response: { 200: z.object({ transaction: z.string() }) },
      },
    },
    async (req) => {
      enforce(s, `wallet:${req.wallet}:refresh`, 30, 60);
      return refreshBlockhash(s, req.wallet!, req.body.transaction);
    },
  );

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
