import { existsSync, readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { makeWorld } from "./helpers/world.js";

const committed = new URL("../../docs/openapi.json", import.meta.url);

describe("OpenAPI document", () => {
  it("documents every endpoint with a summary and tag", async () => {
    const w = await makeWorld();
    const doc = w.app.swagger() as { paths: Record<string, Record<string, { summary?: string; tags?: string[] }>> };
    const paths = Object.keys(doc.paths);
    for (const p of [
      "/v1/health", "/v1/meta", "/v1/auth/nonce", "/v1/auth/verify",
      "/v1/challenges", "/v1/challenges/{pool}", "/v1/challenges/tx/create", "/v1/challenges/tx/join", "/v1/challenges/tx/claim", "/v1/challenges/sync",
      "/v1/devices", "/v1/devices/challenge", "/v1/devices/register", "/v1/proofs/session", "/v1/proofs/submit",
      "/v1/squads", "/v1/squads/join", "/v1/squads/{id}", "/v1/squads/{id}/invite", "/v1/squads/{id}/challenges", "/v1/squads/{id}/feed",
      "/v1/squads/{id}/leaderboard", "/v1/squads/{id}/nudge", "/v1/push/register", "/v1/push/unregister", "/v1/coach/suggestions",
    ]) expect(paths, p).toContain(p);
    for (const [path, ops] of Object.entries(doc.paths)) {
      for (const [method, op] of Object.entries(ops)) {
        expect(op.summary, `${method} ${path} summary`).toBeTruthy();
        expect(op.tags?.length, `${method} ${path} tags`).toBeGreaterThan(0);
      }
    }
  });

  it("docs/openapi.json is up to date (run `npm run openapi` after changing routes)", async () => {
    if (!existsSync(committed)) return; // not available in the WSL work copy
    const w = await makeWorld();
    expect(JSON.parse(JSON.stringify(w.app.swagger()))).toEqual(JSON.parse(readFileSync(committed, "utf8")));
  });
});
