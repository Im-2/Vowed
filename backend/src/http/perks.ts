import type { FastifyInstance } from "fastify";
import type { ZodTypeProvider } from "fastify-type-provider-zod";
import { z } from "zod";
import { buildFreezeTx, FREEZE_LOOKBACK_DAYS, redeemFreeze } from "../perks/freeze.js";
import { rewardsStatus } from "../rewards/service.js";
import type { Services } from "../services.js";
import { authenticate, pubkeySchema } from "./auth.js";
import { enforce } from "./ratelimit.js";

export function registerPerkRoutes(app: FastifyInstance, s: Services) {
  const r = app.withTypeProvider<ZodTypeProvider>();
  const auth = authenticate(s);
  const body = z.object({ pool: pubkeySchema, dayIndex: z.number().int().min(0).max(59) });

  r.get(
    "/v1/rewards",
    {
      preHandler: auth,
      schema: {
        tags: ["rewards"],
        summary: "Weekly SKR rewards for the top streaks: the running week's standings and the rewards this wallet has received",
        response: {
          200: z.object({
            enabled: z.boolean(), label: z.string(), includesDemoPools: z.boolean(), minStreak: z.number(), ladder: z.array(z.string()),
            currentWeek: z.number(), weekEndsAt: z.number(),
            standings: z.array(z.object({ rank: z.number(), wallet: z.string(), streak: z.number(), you: z.boolean(), qualifies: z.boolean() })),
            mine: z.array(z.object({ week: z.number(), rank: z.number(), streak: z.number(), amount: z.string(), status: z.string(), signature: z.string().nullable() })),
          }),
        },
      },
    },
    async (req) => rewardsStatus(s, req.wallet!),
  );

  r.post(
    "/v1/perks/freeze/tx",
    {
      preHandler: auth,
      schema: {
        tags: ["rewards"],
        summary: `Build the unsigned SKR payment for a streak freeze on a missed day (within ${FREEZE_LOOKBACK_DAYS} days). The app must check the transaction before signing`,
        body,
        response: { 200: z.object({ price: z.string(), mint: z.string(), payee: z.string(), payeeToken: z.string(), decimals: z.number(), label: z.string(), transaction: z.string() }) },
      },
    },
    async (req) => {
      enforce(s, `wallet:${req.wallet}:freeze-tx`, 20, 3_600);
      return buildFreezeTx(s, req.wallet!, req.body.pool, req.body.dayIndex);
    },
  );

  r.post(
    "/v1/perks/freeze",
    {
      preHandler: auth,
      schema: {
        tags: ["rewards"],
        summary: "Record a streak freeze once its SKR payment is confirmed on chain. The freeze only affects streak displays and rewards, never check-ins or payouts",
        body: body.extend({ signature: z.string().min(60).max(100) }),
        response: { 200: z.object({ pool: z.string(), dayIndex: z.number() }) },
      },
    },
    async (req) => {
      enforce(s, `wallet:${req.wallet}:freeze`, 20, 3_600);
      return redeemFreeze(s, req.wallet!, req.body.pool, req.body.dayIndex, req.body.signature);
    },
  );
}
