import type { FastifyInstance } from "fastify";
import type { ZodTypeProvider } from "fastify-type-provider-zod";
import { z } from "zod";
import { PROOF_TYPES } from "../domain/plan.js";
import { createSession, submitProof } from "../proofs/service.js";
import { ProofPackageSchema } from "../proofs/verify.js";
import type { Services } from "../services.js";
import { authenticate, pubkeySchema } from "./auth.js";
import { enforce } from "./ratelimit.js";

const checkinOutcome = z.object({ status: z.enum(["confirmed", "pending", "failed"]), signature: z.string().optional(), error: z.string().optional() });

export function registerProofRoutes(app: FastifyInstance, s: Services) {
  const r = app.withTypeProvider<ZodTypeProvider>();
  const auth = authenticate(s);

  r.post(
    "/v1/proofs/session",
    {
      preHandler: auth,
      schema: {
        tags: ["proofs"],
        summary: "Start a proof session: returns the nonce the device must sign into the proof",
        body: z.object({ pool: pubkeySchema, dayIndex: z.number().int().min(0).max(59), proofType: z.enum(PROOF_TYPES) }),
        response: {
          200: z.object({
            sessionId: z.string(),
            nonce: z.string(),
            expiresAt: z.number(),
            proofType: z.enum(PROOF_TYPES),
            dayIndex: z.number(),
            target: z.object({ metric: z.string(), value: z.number(), unit: z.string(), direction: z.enum(["atLeast", "atMost"]) }),
            window: z.object({ opensAt: z.number(), closesAt: z.number() }),
          }),
        },
      },
    },
    async (req) => {
      enforce(s, `wallet:${req.wallet}:psession`, 30, 60);
      return createSession(s, req.wallet!, req.body.pool, req.body.dayIndex, req.body.proofType);
    },
  );

  r.post(
    "/v1/proofs/submit",
    {
      preHandler: auth,
      schema: {
        tags: ["proofs"],
        summary: "Submit a device-signed proof package. Idempotent per session: a retry returns the same result",
        body: ProofPackageSchema,
        response: {
          200: z.object({
            accepted: z.literal(true),
            trustTier: z.enum(["high", "medium", "low"]),
            daysCompleted: z.number(),
            streak: z.number(),
            checkin: checkinOutcome,
          }),
        },
      },
    },
    async (req) => {
      enforce(s, `wallet:${req.wallet}:psubmit`, 30, 60);
      return submitProof(s, req.wallet!, req.body);
    },
  );
}
