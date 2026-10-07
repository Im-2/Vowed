import fastifySwagger from "@fastify/swagger";
import Fastify, { type FastifyInstance } from "fastify";
import {
  hasZodFastifySchemaValidationErrors,
  isResponseSerializationError,
  jsonSchemaTransform,
  serializerCompiler,
  validatorCompiler,
} from "fastify-type-provider-zod";
import { ChainError } from "../chain/types.js";
import { ApiError } from "../errors.js";
import type { Services } from "../services.js";
import { registerAuthRoutes } from "./auth.js";
import { registerChallengeRoutes } from "./challenges.js";
import { registerDeviceRoutes } from "./devices.js";
import { registerFaucetRoutes } from "./faucet.js";
import { registerGoalRoutes } from "./goals.js";
import { registerProofRoutes } from "./proofs.js";
import { enforce } from "./ratelimit.js";
import { registerSquadRoutes } from "./squads.js";

export async function buildApp(s: Services, opts: { logger?: boolean } = {}): Promise<FastifyInstance> {
  const app = Fastify({
    bodyLimit: 64 * 1024,
    logger: opts.logger ? { level: "info" } : false,
    // behind a reverse proxy, set trustProxy so rate limits use the client IP
    trustProxy: false,
  });
  app.setValidatorCompiler(validatorCompiler);
  app.setSerializerCompiler(serializerCompiler);

  await app.register(fastifySwagger, {
    openapi: {
      openapi: "3.0.3",
      info: {
        title: "Vowed API",
        version: "0.1.0",
        description:
          "Backend for the Vowed Android app. All endpoints except /v1/health, /v1/meta and /v1/auth/* need `Authorization: Bearer <token>` from /v1/auth/verify. u64 amounts are decimal strings. Transaction builders return unsigned base64 transactions: the app must decode and check them before asking the wallet to sign.",
      },
      components: { securitySchemes: { bearerAuth: { type: "http", scheme: "bearer", bearerFormat: "JWT" } } },
      security: [{ bearerAuth: [] }],
      tags: [
        { name: "auth" }, { name: "meta" }, { name: "challenges" }, { name: "devices" },
        { name: "proofs" }, { name: "squads" }, { name: "push" }, { name: "coach" },
      ],
    },
    transform: jsonSchemaTransform,
  });

  app.addHook("onRequest", async (req) => {
    enforce(s, `ip:${req.ip}`, 300, 60);
  });

  app.setErrorHandler((err, req, reply) => {
    if (err instanceof ApiError) {
      return reply.status(err.status).send({ error: { code: err.code, message: err.message, details: err.details } });
    }
    if (hasZodFastifySchemaValidationErrors(err)) {
      return reply.status(400).send({
        error: { code: "validation_error", message: "request does not match the schema", details: err.validation.map((v) => ({ path: v.instancePath, message: v.message })) },
      });
    }
    if (isResponseSerializationError(err)) {
      req.log.error({ url: req.url }, "response serialization failed");
      return reply.status(500).send({ error: { code: "internal", message: "internal error" } });
    }
    if (err instanceof ChainError) {
      const code = err.anchorError?.code ?? "chain_error";
      return reply.status(err.anchorError ? 409 : 502).send({ error: { code, message: err.anchorError ? `program rejected the transaction: ${code}` : "the Solana network request failed" } });
    }
    const status = (err as { statusCode?: number }).statusCode;
    if (status && status >= 400 && status < 500) {
      return reply.status(status).send({ error: { code: status === 413 ? "payload_too_large" : "bad_request", message: status === 413 ? "request body too large" : "bad request" } });
    }
    req.log.error({ err: { name: (err as Error).name, message: (err as Error).message }, url: req.url }, "unhandled error");
    return reply.status(500).send({ error: { code: "internal", message: "internal error" } });
  });

  app.get("/v1/health", { schema: { tags: ["meta"], summary: "Liveness", security: [] } }, async () => ({ ok: true, service: "vowed-backend" }));
  app.get("/v1/openapi.json", { schema: { hide: true } }, async () => app.swagger());

  registerAuthRoutes(app, s);
  registerChallengeRoutes(app, s);
  registerDeviceRoutes(app, s);
  registerProofRoutes(app, s);
  registerSquadRoutes(app, s);
  registerFaucetRoutes(app, s);
  registerGoalRoutes(app, s);

  await app.ready();
  return app;
}
