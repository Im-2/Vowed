import { PublicKey } from "@solana/web3.js";
import type { FastifyInstance } from "fastify";
import type { ZodTypeProvider } from "fastify-type-provider-zod";
import { z } from "zod";
import { solCost, type CostKind } from "../chain/costs.js";
import { getChallenge } from "../challenges/sync.js";
import { badRequest, notFound } from "../errors.js";
import type { Services } from "../services.js";
import { ataAddress, tokenAmount } from "../util/token.js";
import { authenticate, pubkeySchema } from "./auth.js";
import { enforce } from "./ratelimit.js";

const u64 = z.string().regex(/^\d{1,20}$/);

export interface PreflightResult {
  kind: CostKind;
  ok: boolean;
  sol: { balance: string; needed: string; enough: boolean; fee: string; rent: string; keep: string };
  token: { mint: string; symbol: string | null; balance: string; needed: string; accountExists: boolean; enough: boolean } | null;
  reasons: { code: "low_sol" | "no_token_account" | "low_tokens"; message: string }[];
}

function symbolOf(s: Services, mint: string): string | null {
  if (mint === s.config.FAUCET_USDC_MINT) return "tUSDC";
  if (mint === s.config.FAUCET_SKR_MINT) return "tSKR";
  return null;
}

/**
 * Can this wallet afford the transaction it is about to sign? Checked before the wallet is opened: enough devnet SOL for the fee, the rent of any
 * account the transaction creates and the small balance a wallet must keep; and, for a stake, a token account that holds the stake.
 */
export async function preflight(s: Services, wallet: string, kind: CostKind, input: { pool?: string; mint?: string; stake?: string }): Promise<PreflightResult> {
  const owner = new PublicKey(wallet);
  let mint: string | undefined = input.mint;
  let stake = input.stake ? BigInt(input.stake) : 0n;
  if (kind === "join") {
    if (!input.pool) throw badRequest("pool_required", "a join check needs the challenge address");
    const c = getChallenge(s, input.pool);
    if (!c) throw notFound("challenge");
    mint = c.mint;
  }
  if (kind === "claim" && input.pool) {
    const c = getChallenge(s, input.pool);
    if (c) mint = c.mint;
  }
  if (kind === "freeze") {
    mint = s.config.FAUCET_SKR_MINT;
    stake = s.config.PERK_FREEZE_PRICE;
  }

  const acc = await s.chain.getAccount(wallet);
  const balance = acc?.lamports ?? 0n;
  const tokenAcc = mint ? await s.chain.getAccount(ataAddress(owner, new PublicKey(mint)).toBase58()) : null;
  const cost = await solCost(s.chain, kind, { userTokenAccountMissing: Boolean(mint) && !tokenAcc, thenJoin: kind === "create" && stake > 0n });
  const reasons: PreflightResult["reasons"] = [];

  const solOk = balance >= cost.total;
  if (!solOk) {
    reasons.push({ code: "low_sol", message: "Your wallet needs a little devnet SOL for network fees. Tap Get test tokens to receive some." });
  }
  let token: PreflightResult["token"] = null;
  if (mint && stake > 0n && kind !== "claim") {
    const tokenBalance = tokenAcc ? tokenAmount(tokenAcc.data) : 0n;
    const enough = tokenBalance >= stake;
    token = { mint, symbol: symbolOf(s, mint), balance: tokenBalance.toString(), needed: stake.toString(), accountExists: Boolean(tokenAcc), enough };
    const name = token.symbol ?? "test tokens";
    if (!tokenAcc) reasons.push({ code: "no_token_account", message: `Your wallet has no ${name} yet. Tap Get test tokens to receive some.` });
    else if (!enough) reasons.push({ code: "low_tokens", message: `You do not have enough ${name} for this stake. Tap Get test tokens to receive some.` });
  }
  return {
    kind,
    ok: reasons.length === 0,
    sol: { balance: balance.toString(), needed: cost.total.toString(), enough: solOk, fee: cost.fee.toString(), rent: cost.rent.toString(), keep: cost.keep.toString() },
    token,
    reasons,
  };
}

export function registerPreflightRoutes(app: FastifyInstance, s: Services) {
  const r = app.withTypeProvider<ZodTypeProvider>();
  const auth = authenticate(s);
  r.get(
    "/v1/preflight",
    {
      preHandler: auth,
      schema: {
        tags: ["challenges"],
        summary: "Can this wallet afford the transaction it is about to sign? Checks devnet SOL (fee, rent of new accounts, the balance a wallet must keep) and, for a stake, the token balance, so the app can say so BEFORE it opens the wallet",
        querystring: z.object({ kind: z.enum(["create", "join", "claim", "freeze"]), pool: pubkeySchema.optional(), mint: pubkeySchema.optional(), stake: u64.optional() }),
        response: {
          200: z.object({
            kind: z.enum(["create", "join", "claim", "freeze"]),
            ok: z.boolean(),
            sol: z.object({ balance: z.string(), needed: z.string(), enough: z.boolean(), fee: z.string(), rent: z.string(), keep: z.string() }),
            token: z.object({ mint: z.string(), symbol: z.string().nullable(), balance: z.string(), needed: z.string(), accountExists: z.boolean(), enough: z.boolean() }).nullable(),
            reasons: z.array(z.object({ code: z.enum(["low_sol", "no_token_account", "low_tokens"]), message: z.string() })),
          }),
        },
      },
    },
    async (req) => {
      enforce(s, `wallet:${req.wallet}:preflight`, 60, 60);
      return preflight(s, req.wallet!, req.query.kind, req.query);
    },
  );
}
