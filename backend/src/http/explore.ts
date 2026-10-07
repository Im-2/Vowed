import type { FastifyInstance } from "fastify";
import type { ZodTypeProvider } from "fastify-type-provider-zod";
import { z } from "zod";
import { exploreQuery, listExplore, REPORT_REASONS, reportChallenge } from "../explore/service.js";
import type { Services } from "../services.js";
import { authenticate, pubkeySchema } from "./auth.js";
import { enforce } from "./ratelimit.js";

const item = z.object({
  pool: z.string(),
  title: z.string(),
  category: z.string(),
  proofType: z.string().nullable(),
  trustTier: z.string().nullable(),
  mode: z.string(),
  mint: z.string(),
  tokenSymbol: z.string(),
  tokenIsTest: z.boolean(),
  durationDays: z.number(),
  requiredDays: z.number(),
  startTs: z.number(),
  joinDeadlineTs: z.number(),
  participantCount: z.number(),
  maxParticipants: z.number(),
  totalDeposits: z.string(),
  stakeCap: z.string().nullable(),
  isDemo: z.boolean(),
  daySecs: z.number(),
  demoLabel: z.string().nullable(),
  sample: z.boolean(),
  createdByYou: z.boolean(),
  joined: z.boolean(),
});

export function registerExploreRoutes(app: FastifyInstance, s: Services) {
  const r = app.withTypeProvider<ZodTypeProvider>();
  const auth = authenticate(s);

  r.get(
    "/v1/explore",
    {
      preHandler: auth,
      schema: {
        tags: ["explore"],
        summary: "Public challenges you can join, with filters and pages. Only the goal text and public pool data are returned",
        querystring: exploreQuery,
        response: { 200: z.object({ items: z.array(item), nextCursor: z.string().nullable() }) },
      },
    },
    async (req) => {
      enforce(s, `wallet:${req.wallet}:explore`, 120, 60);
      return listExplore(s, req.wallet!, req.query);
    },
  );

  r.post(
    "/v1/explore/:pool/report",
    {
      preHandler: auth,
      schema: {
        tags: ["explore"],
        summary: "Report a public challenge (spam, offensive, scam, other). Several reports from different people hide it",
        params: z.object({ pool: pubkeySchema }),
        body: z.object({ reason: z.enum(REPORT_REASONS), note: z.string().max(200).optional() }),
        response: { 200: z.object({ ok: z.literal(true) }) },
      },
    },
    async (req) => {
      enforce(s, `wallet:${req.wallet}:report-day`, 20, 86_400);
      reportChallenge(s, req.wallet!, req.params.pool, req.body.reason, req.body.note);
      return { ok: true as const }; // the answer never says whether this report hid the challenge
    },
  );
}
