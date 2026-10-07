import type { FastifyInstance } from "fastify";
import type { ZodTypeProvider } from "fastify-type-provider-zod";
import { z } from "zod";
import { claimTestTokens, faucetStatus } from "../faucet/service.js";
import type { Services } from "../services.js";
import { authenticate } from "./auth.js";
import { enforce } from "./ratelimit.js";

const token = z.object({ symbol: z.enum(["tUSDC", "tSKR"]), name: z.string(), mint: z.string(), amount: z.string(), decimals: z.number() });

export function registerFaucetRoutes(app: FastifyInstance, s: Services) {
  const r = app.withTypeProvider<ZodTypeProvider>();
  const auth = authenticate(s);

  r.get(
    "/v1/faucet",
    {
      preHandler: auth,
      schema: {
        tags: ["faucet"],
        summary: "Test-token faucet status for the signed-in wallet (devnet only). Show `label` next to the tokens",
        response: {
          200: z.object({
            enabled: z.boolean(),
            network: z.string(),
            label: z.string(),
            tokens: z.array(token),
            canClaim: z.boolean(),
            nextClaimAt: z.number(),
            claimsLeftToday: z.number(),
            balances: z.object({ tUSDC: z.string(), tSKR: z.string() }),
          }),
        },
      },
    },
    async (req) => {
      enforce(s, `wallet:${req.wallet}:faucet-status`, 60, 60);
      return faucetStatus(s, req.wallet!);
    },
  );

  r.post(
    "/v1/faucet/claim",
    {
      preHandler: auth,
      schema: {
        tags: ["faucet"],
        summary: "Claim a fixed amount of TEST USDC and TEST SKR (once per wallet per 24 hours, with a global daily cap)",
        response: {
          200: z.object({
            signature: z.string(),
            minted: z.object({ tUSDC: z.string(), tSKR: z.string() }),
            nextClaimAt: z.number(),
            label: z.string(),
          }),
        },
      },
    },
    async (req) => {
      enforce(s, `wallet:${req.wallet}:faucet-claim`, 10, 60); // hammering the button never reaches the chain
      return claimTestTokens(s, req.wallet!);
    },
  );
}
