import type { FastifyInstance } from "fastify";
import type { ZodTypeProvider } from "fastify-type-provider-zod";
import { z } from "zod";
import { CATALOG, diverseExamples } from "../goals/catalog.js";
import { MAX_GOAL_CHARS, parseGoal } from "../goals/parser.js";
import { demoize } from "../goals/catalog.js";
import { validatePlan } from "../goals/validate.js";
import { GoalPlanSchema } from "../domain/plan.js";
import type { Services } from "../services.js";
import { authenticate } from "./auth.js";
import { enforce } from "./ratelimit.js";

const planOption = z.object({
  label: z.string(),
  plan: GoalPlanSchema,
  demoPlan: GoalPlanSchema.nullable(),
  extras: z.object({ needsPlace: z.boolean(), needsApp: z.boolean(), limitations: z.array(z.string()) }),
});

const parseResponse = z.object({
  status: z.enum(["plan", "unverifiable", "unclear"]),
  source: z.enum(["template", "ai", "cache", "template-after-ai-failed", "none"]),
  plan: GoalPlanSchema.nullable(),
  /** The same goal with a tiny amount a minutes-long demo "day" can reach; use it for DEMO pools. */
  demoPlan: GoalPlanSchema.nullable(),
  templateId: z.string().nullable(),
  trustTier: z.enum(["high", "medium", "low"]).nullable(),
  needsPlace: z.boolean(),
  needsApp: z.boolean(),
  limitations: z.array(z.string()),
  notes: z.array(z.string()),
  confidence: z.enum(["high", "medium", "low"]),
  reason: z.string().nullable(),
  suggestedAlternative: z.string().nullable(),
  alternatives: z.array(planOption),
  clarifyingQuestions: z.array(z.string()),
  examples: z.array(z.string()),
  ai: z.object({ used: z.boolean(), note: z.string().nullable() }),
});

export function registerGoalRoutes(app: FastifyInstance, s: Services) {
  const r = app.withTypeProvider<ZodTypeProvider>();
  const auth = authenticate(s);

  r.get(
    "/v1/goals/templates",
    {
      preHandler: auth,
      schema: {
        tags: ["goals"],
        summary: "The built-in goal templates (they work without any AI)",
        response: {
          200: z.object({
            templates: z.array(z.object({ id: z.string(), title: z.string(), example: z.string(), summary: z.string(), proofType: z.string(), category: z.string(), needsPlace: z.boolean(), needsApp: z.boolean() })),
          }),
        },
      },
    },
    async () => ({
      templates: CATALOG.map((t) => ({ id: t.id, title: t.title, example: t.example, summary: t.summary, proofType: t.proofType, category: t.category, needsPlace: t.needsPlace, needsApp: t.needsApp })),
    }),
  );

  r.get(
    "/v1/goals/examples",
    {
      preHandler: auth,
      schema: {
        tags: ["goals"],
        summary: "A rotating, mixed list of example goals (study, steps, screen time, sleep, places and more; rep counting is one of many)",
        querystring: z.object({ count: z.coerce.number().int().min(3).max(13).default(8), seed: z.coerce.number().int().min(0).max(2_000_000_000).optional() }),
        response: {
          200: z.object({ examples: z.array(z.object({ text: z.string(), templateId: z.string(), family: z.string(), category: z.string(), proofType: z.string() })) }),
        },
      },
    },
    async (req) => ({ examples: diverseExamples(req.query.count, req.query.seed ?? Math.floor(Math.random() * 1e9)) }),
  );

  r.post(
    "/v1/goals/parse",
    {
      preHandler: auth,
      schema: {
        tags: ["goals"],
        summary: "Turn a goal typed in plain words into a verified plan. Only this text is ever sent to the language model",
        body: z.object({
          text: z.string().min(1).max(MAX_GOAL_CHARS * 2),
          /** false keeps the goal on this server and uses only the built-in templates */
          useAi: z.boolean().default(true),
        }),
        response: { 200: parseResponse },
      },
    },
    async (req) => {
      enforce(s, `wallet:${req.wallet}:goal-parse`, 40, 600);
      return parseGoal(s, req.wallet!, req.body.text, req.body.useAi);
    },
  );

  r.post(
    "/v1/goals/validate",
    {
      preHandler: auth,
      schema: {
        tags: ["goals"],
        summary: "Check (and normalise) a plan the user edited before staking",
        body: z.object({ plan: z.unknown(), demo: z.boolean().default(false) }),
        response: {
          200: z.object({ ok: z.boolean(), plan: GoalPlanSchema.nullable(), demoPlan: GoalPlanSchema.nullable(), reason: z.string().nullable(), notes: z.array(z.string()) }),
        },
      },
    },
    async (req) => {
      enforce(s, `wallet:${req.wallet}:goal-validate`, 120, 600);
      const v = validatePlan(req.body.plan, { demo: req.body.demo });
      if (!v.ok) return { ok: false, plan: null, demoPlan: null, reason: v.reason, notes: [] };
      let demoPlan = null;
      try {
        const d = demoize(v.plan);
        demoPlan = validatePlan(d, { demo: true }).ok ? d : null;
      } catch {
        demoPlan = null;
      }
      return { ok: true, plan: v.plan, demoPlan, reason: null, notes: v.notes };
    },
  );
}
