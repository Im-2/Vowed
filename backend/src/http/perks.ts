import type { FastifyInstance } from "fastify";
import type { ZodTypeProvider } from "fastify-type-provider-zod";
import { z } from "zod";
import { buildFreezeTx, FREEZE_LOOKBACK_DAYS, redeemFreeze } from "../perks/freeze.js";
import { leaderboard, setLeaderboardHidden } from "../rewards/leaderboard.js";
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
            currentWeek: z.number(), weekEndsAt: z.number(), rewardsWallet: z.string().nullable(),
            standings: z.array(z.object({ rank: z.number(), wallet: z.string(), streak: z.number(), you: z.boolean(), qualifies: z.boolean() })),
            mine: z.array(z.object({ week: z.number(), rank: z.number(), streak: z.number(), amount: z.string(), status: z.string(), signature: z.string().nullable() })),
          }),
        },
      },
    },
    async (req) => rewardsStatus(s, req.wallet!),
  );

  r.get(
    "/v1/leaderboard",
    {
      preHandler: auth,
      schema: {
        tags: ["rewards"],
        summary: "Leaderboard for the Rewards screen: this week live standings or the all-time longest streaks. Only an avatar seed (wallet), a short name and a streak number are shown for other people; SAMPLE rows are flagged",
        querystring: z.object({ scope: z.enum(["week", "all"]).default("week") }),
        response: {
          200: z.object({
            scope: z.enum(["week", "all"]), label: z.string(), enabled: z.boolean(), weekEndsAt: z.number(), minStreak: z.number(),
            entries: z.array(z.object({ rank: z.number(), wallet: z.string(), name: z.string(), streak: z.number(), reward: z.string().nullable(), you: z.boolean(), sample: z.boolean() })),
            me: z.object({ rank: z.number().nullable(), streak: z.number(), rewardIfNow: z.string().nullable(), hidden: z.boolean() }),
            hasSamples: z.boolean(), sampleNote: z.string().nullable(),
          }),
        },
      },
    },
    async (req) => leaderboard(s, req.wallet!, req.query.scope),
  );

  r.post(
    "/v1/profile/leaderboard",
    {
      preHandler: auth,
      schema: {
        tags: ["rewards"],
        summary: "Hide this wallet from the public leaderboards (or show it again). A hidden wallet still earns the weekly reward",
        body: z.object({ hidden: z.boolean() }),
        response: { 200: z.object({ hidden: z.boolean() }) },
      },
    },
    async (req) => {
      enforce(s, `wallet:${req.wallet}:lb-hide`, 60, 3_600);
      setLeaderboardHidden(s, req.wallet!, req.body.hidden);
      return { hidden: req.body.hidden };
    },
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
