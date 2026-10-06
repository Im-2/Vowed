import Fastify from "fastify";

export function buildApp() {
  const app = Fastify({ logger: false, bodyLimit: 64 * 1024 });
  app.get("/v1/health", async () => ({ ok: true, service: "vowed-backend" }));
  return app;
}
