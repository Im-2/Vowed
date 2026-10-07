import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { buildFromTemplate, CATALOG, demoize, diverseExamples } from "../src/goals/catalog.js";
import { matchGoal } from "../src/goals/matcher.js";
import { validatePlan } from "../src/goals/validate.js";
import { GoalPlanSchema, PROOF_TRUST } from "../src/domain/plan.js";

interface Sample {
  text: string;
  template: string;
  type: string;
  value: number;
  unit: string;
  direction: string;
  totalDays?: number;
  window?: [string, string];
  app?: string;
}
const samples = JSON.parse(readFileSync(new URL("./fixtures/goal-samples.json", import.meta.url), "utf8")) as Sample[];

describe("goal template catalog", () => {
  it("has the 13 templates SPEC 6.3 asks for, each producing a valid plan", () => {
    expect(CATALOG.length).toBeGreaterThanOrEqual(13);
    expect(new Set(CATALOG.map((t) => t.id)).size).toBe(CATALOG.length);
    for (const t of CATALOG) {
      const plan = buildFromTemplate(t);
      expect(GoalPlanSchema.safeParse(plan).success, t.id).toBe(true);
      const v = validatePlan(plan);
      expect(v.ok, `${t.id}: ${v.ok ? "" : v.reason}`).toBe(true);
    }
  });

  it("covers every kind of goal in the spec", () => {
    const ids = CATALOG.map((t) => t.id);
    for (const need of ["squats", "pushups", "steps", "gym", "focus", "usage-limit", "no-use-window", "early-wake", "walk-outside", "meditation", "reading", "hydration", "sleep-window"]) {
      expect(ids, need).toContain(need);
    }
  });

  it("every template's demo version is valid for demo pools and refused for normal pools when it is below the normal minimum", () => {
    for (const t of CATALOG) {
      const plan = buildFromTemplate(t);
      const demo = demoize(plan);
      expect(validatePlan(demo, { demo: true }).ok, `${t.id} demo`).toBe(true);
      if (t.demo) expect(validatePlan(demo, { demo: false }).ok, `${t.id} demo plan must not pass as a normal pool`).toBe(false);
    }
  });
});

describe("template matcher (no AI)", () => {
  it.each(samples)("parses: $text", (s) => {
    const m = matchGoal(s.text);
    expect(m?.kind).toBe("plan");
    if (m?.kind !== "plan") return;
    expect(m.templateId).toBe(s.template);
    const p = m.plan;
    expect(p.proofMethods[0]!.type).toBe(s.type);
    expect(p.target).toMatchObject({ value: s.value, unit: s.unit, direction: s.direction });
    expect(p.proofMethods[0]!.trustTier).toBe(PROOF_TRUST[s.type as keyof typeof PROOF_TRUST]);
    if (s.totalDays) expect(p.cadence.totalDays).toBe(s.totalDays);
    if (s.window) expect(p.window).toEqual({ startLocalTime: s.window[0], endLocalTime: s.window[1] });
    if (s.app) expect(p.proofMethods[0]!.params.app).toBe(s.app);
    expect(validatePlan(p).ok).toBe(true);
  });

  it("reads amounts in words, thousands separators, k and units", () => {
    const get = (t: string) => {
      const m = matchGoal(t);
      return m?.kind === "plan" ? m.plan.target : null;
    };
    expect(get("walk 12,500 steps")).toMatchObject({ value: 12500, unit: "steps" });
    expect(get("study 90 minutes a day")).toMatchObject({ value: 90, unit: "minutes" });
    expect(get("study for an hour")).toMatchObject({ value: 1, unit: "hours" });
    expect(get("do thirty squats")).toMatchObject({ value: 30, unit: "reps" });
  });

  it("reads the length of the challenge", () => {
    const days = (t: string) => {
      const m = matchGoal(t);
      return m?.kind === "plan" ? m.plan.cadence : null;
    };
    expect(days("do 20 squats for 30 days")).toMatchObject({ totalDays: 30 });
    expect(days("do 20 squats for 3 weeks")).toMatchObject({ totalDays: 21 });
    expect(days("do 20 squats for a week")).toMatchObject({ totalDays: 7 });
    expect(days("do 20 squats")).toMatchObject({ totalDays: 7, requiredDays: 6 });
  });

  it("says plainly when an amount was not found and a default was used", () => {
    const m = matchGoal("go to the gym");
    expect(m?.kind).toBe("plan");
    if (m?.kind === "plan") {
      expect(m.confidence).toBe("medium");
      expect(m.notes.join(" ")).toMatch(/did not find an amount/);
    }
  });

  it.each([
    ["I want to lose 5 kg", /weigh/i],
    ["eat healthier food", /eat/i],
    ["stop smoking", /smoked/i],
    ["be happier", /feelings/i],
    ["save more money", /bank/i],
    ["sleep better", /sleep/i],
    ["call my mom every day", /who you spoke/i],
  ])("recognises a goal a phone cannot verify: %s", (text, reason) => {
    const m = matchGoal(text);
    expect(m?.kind).toBe("unverifiable");
    if (m?.kind === "unverifiable") {
      expect(m.reason).toMatch(reason);
      expect(m.suggestedAlternative.length).toBeGreaterThan(10);
    }
  });

  it("still accepts a concrete action next to a vague aim", () => {
    const m = matchGoal("walk 8000 steps to lose weight");
    expect(m?.kind).toBe("plan");
  });

  it("returns nothing for text that is not a goal", () => {
    expect(matchGoal("asdf qwer zxcv")).toBeUndefined();
    expect(matchGoal("")).toBeUndefined();
  });
});

describe("plan validation", () => {
  const good = () => buildFromTemplate(CATALOG.find((t) => t.id === "steps")!);

  it("refuses unknown fields and wrong structure", () => {
    expect(validatePlan({ ...good(), admin: true }).ok).toBe(false);
    expect(validatePlan({ title: "x" }).ok).toBe(false);
    expect(validatePlan("steps").ok).toBe(false);
    expect(validatePlan(null).ok).toBe(false);
  });

  it("forces the trust tier of the proof type, whatever the plan claims", () => {
    const p = good();
    p.proofMethods[0]!.trustTier = "high";
    const v = validatePlan(p);
    expect(v.ok && v.plan.proofMethods[0]!.trustTier).toBe("medium");
    const self = { ...good(), proofMethods: [{ type: "SELF_ATTEST", params: {}, trustTier: "high" }], target: { metric: "done", value: 1, unit: "times", direction: "atLeast" } };
    const s = validatePlan(self);
    expect(s.ok && s.plan.proofMethods[0]!.trustTier).toBe("low");
  });

  it("refuses unrealistic or trivial amounts and the wrong direction", () => {
    const set = (value: number, direction: "atLeast" | "atMost" = "atLeast") => ({ ...good(), target: { ...good().target, value, direction } });
    expect(validatePlan(set(10_000_000)).ok).toBe(false);
    expect(validatePlan(set(3)).ok).toBe(false);
    expect(validatePlan(set(8000, "atMost")).ok).toBe(false);
    expect(validatePlan(set(-5)).ok).toBe(false);
    expect(validatePlan(set(Number.NaN)).ok).toBe(false);
    expect(validatePlan(set(8000)).ok).toBe(true);
  });

  it("refuses proof types that are not offered, and drops extra proof methods", () => {
    const camera = { ...good(), proofMethods: [{ type: "ROOT_ACCESS", params: {}, trustTier: "high" }] };
    expect(validatePlan(camera).ok).toBe(false);
    const two = { ...good(), proofMethods: [...good().proofMethods, { type: "SELF_ATTEST", params: {}, trustTier: "low" }] };
    const v = validatePlan(two);
    expect(v.ok && v.plan.proofMethods).toHaveLength(1);
  });

  it("keeps only whitelisted parameters and sanitises text", () => {
    const geo = buildFromTemplate(CATALOG.find((t) => t.id === "gym")!, { place: "My gym" });
    const dirty = { ...geo, title: "Gym‮ <b>now</b>\u0000", proofMethods: [{ type: "GEOFENCE", params: { place: "Gym", radiusM: "99999", lat: "51.5", lon: "-0.1", evil: "x" }, trustTier: "medium" }] };
    const v = validatePlan(dirty);
    expect(v.ok).toBe(true);
    if (v.ok) {
      expect(v.plan.proofMethods[0]!.params).toEqual({ place: "Gym", radiusM: "2000" }); // coordinates and unknown keys are dropped
      expect(v.plan.title).not.toMatch(/[<>‮\u0000]/);
    }
  });

  it("an app-usage plan must name the app, and an app-free window needs a time window", () => {
    const usage = buildFromTemplate(CATALOG.find((t) => t.id === "usage-limit")!);
    usage.proofMethods[0]!.params = {};
    expect(validatePlan(usage).ok).toBe(false);
    const nu = buildFromTemplate(CATALOG.find((t) => t.id === "no-use-window")!);
    expect(validatePlan({ ...nu, window: null }).ok).toBe(false);
  });

  it("an unverifiable plan is never accepted as a plan", () => {
    const v = validatePlan({ ...good(), verifiable: false, unverifiableReason: "not measurable" });
    expect(v.ok).toBe(false);
  });

  it("adjusts required days to fit the length", () => {
    const p = { ...good(), cadence: { periodDays: 1 as const, totalDays: 5, requiredDays: 5 } };
    expect(validatePlan(p).ok).toBe(true);
    expect(GoalPlanSchema.safeParse({ ...good(), cadence: { periodDays: 1, totalDays: 5, requiredDays: 9 } }).success).toBe(false);
  });
});

describe("variety of examples", () => {
  it("every worded example of every template parses back to that template with a valid plan", () => {
    let n = 0;
    for (const t of CATALOG) {
      expect(t.examples.length, t.id).toBeGreaterThanOrEqual(2);
      expect(t.examples).toContain(t.example);
      for (const text of t.examples) {
        const m = matchGoal(text);
        expect(m?.kind, text).toBe("plan");
        if (m?.kind === "plan") {
          expect(m.templateId, text).toBe(t.id);
          expect(validatePlan(m.plan).ok, text).toBe(true);
        }
        n++;
      }
    }
    expect(n).toBeGreaterThanOrEqual(35);
  });

  it("a list of eight examples always mixes every kind of goal, and rep counting is at most one of them", () => {
    for (let seed = 0; seed < 300; seed++) {
      const list = diverseExamples(8, seed);
      expect(list).toHaveLength(8);
      const families = new Set(list.map((e) => e.family));
      for (const f of ["steps", "study", "place", "screen", "sleep", "custom"]) expect(families.has(f as never), `seed ${seed} lacks ${f}`).toBe(true);
      expect(list.filter((e) => e.proofType === "CAMERA_POSE").length, `seed ${seed}`).toBeLessThanOrEqual(1);
      expect(new Set(list.map((e) => e.text)).size).toBe(8);
      expect(new Set(list.map((e) => e.templateId)).size).toBe(8);
    }
  });

  it("is repeatable for one seed and rotates across seeds", () => {
    expect(diverseExamples(8, 42)).toEqual(diverseExamples(8, 42));
    const firsts = new Set(Array.from({ length: 40 }, (_, i) => diverseExamples(8, i).map((e) => e.text).join("|")));
    expect(firsts.size).toBeGreaterThan(25);
    const heads = new Set(Array.from({ length: 40 }, (_, i) => diverseExamples(8, i)[0]!.family));
    expect(heads.size).toBeGreaterThanOrEqual(5); // the first thing people see is not always the same kind of goal
  });

  it("short lists leave the camera out entirely", () => {
    for (let seed = 0; seed < 100; seed++) expect(diverseExamples(5, seed).some((e) => e.proofType === "CAMERA_POSE")).toBe(false);
  });

  it("the example texts the API offers are all real examples", () => {
    const all = new Set(CATALOG.flatMap((t) => t.examples));
    for (const e of diverseExamples(13, 7)) expect(all.has(e.text)).toBe(true);
  });
});
