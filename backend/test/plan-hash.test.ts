/** The Android app computes the plan hash itself to check the transaction it is about to sign; both sides must agree. */
import { existsSync, readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { canonicalJson, GoalPlanSchema, planHash } from "../src/domain/plan.js";

function vector(name: string) {
  const candidates = [process.env.VOWED_SHARED && `${process.env.VOWED_SHARED}/test-vectors/${name}`, new URL(`../../shared/test-vectors/${name}`, import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, "$1")].filter(Boolean) as string[];
  const path = candidates.find((p) => existsSync(p));
  if (!path) throw new Error(`vector ${name} not found`);
  return JSON.parse(readFileSync(path, "utf8"));
}

describe("shared plan-hash vectors", () => {
  const v = vector("plan-hash.json");
  it("has cases, every plan is valid, and canonical JSON and hash match", () => {
    expect(v.cases.length).toBeGreaterThanOrEqual(5);
    for (const c of v.cases) {
      const plan = GoalPlanSchema.parse(c.plan);
      expect(canonicalJson(plan)).toBe(c.canonical);
      expect(planHash(plan)).toBe(c.sha256);
    }
  });
  it("a plan with its keys in a different order hashes the same", () => {
    const [first, ...rest] = v.cases;
    const shuffled = rest[rest.length - 1];
    expect(shuffled.sha256).toBe(first.sha256);
    expect(Object.keys(shuffled.plan)[0]).not.toBe(Object.keys(first.plan)[0]);
  });
});
