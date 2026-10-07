/**
 * Goal text -> plan. Order of work:
 *   1. clean the text; look in the cache of earlier model answers;
 *   2. try the deterministic template matcher (free, instant, no data leaves the server);
 *   3. only when the matcher is not sure, and the language model is switched on and within its limits, ask the model;
 *      its reply is untrusted: strict schema, then the semantic validator; anything wrong falls back to step 2's answer or a question;
 *   4. never an unvalidated plan: every plan returned has passed validatePlan.
 */
import { createHash } from "node:crypto";
import { PROOF_TRUST, type GoalPlan } from "../domain/plan.js";
import { tooMany } from "../errors.js";
import { hit } from "../http/ratelimit.js";
import type { Services } from "../services.js";
import { CATALOG, demoize, diverseExamples, extrasFor, type PlanExtras } from "./catalog.js";
import { GeminiError } from "./gemini.js";
import { matchGoal, selfReportAlternative } from "./matcher.js";
import { checkText, NOT_ALLOWED_MESSAGE } from "../moderation/text.js";
import { cleanText, validatePlan } from "./validate.js";

export const MAX_GOAL_CHARS = 300;
const CACHE_TTL_SEC = 7 * 86_400;
const BACKOFF_KEY = "goals:llm-backoff-until";

export interface PlanOption {
  label: string;
  plan: GoalPlan;
  demoPlan: GoalPlan | null;
  extras: PlanExtras;
}

export interface ParseOutcome {
  status: "plan" | "unverifiable" | "unclear";
  source: "template" | "ai" | "cache" | "template-after-ai-failed" | "none";
  plan: GoalPlan | null;
  demoPlan: GoalPlan | null;
  templateId: string | null;
  trustTier: "high" | "medium" | "low" | null;
  needsPlace: boolean;
  needsApp: boolean;
  limitations: string[];
  notes: string[];
  confidence: "high" | "medium" | "low";
  reason: string | null;
  suggestedAlternative: string | null;
  alternatives: PlanOption[];
  clarifyingQuestions: string[];
  examples: string[];
  ai: { used: boolean; note: string | null };
}

const empty = (): ParseOutcome => ({
  status: "unclear", source: "none", plan: null, demoPlan: null, templateId: null, trustTier: null, needsPlace: false, needsApp: false, limitations: [], notes: [],
  confidence: "low", reason: null, suggestedAlternative: null, alternatives: [], clarifyingQuestions: [], examples: [], ai: { used: false, note: null },
});

/** A fresh mix each time: different kinds of goal, never mostly reps. */
const mixedExamples = () => diverseExamples(8, Math.floor(Math.random() * 1e9)).map((e) => e.text);

const normalizeKey = (text: string) => createHash("sha256").update(text.toLowerCase().replace(/\s+/g, " ").trim()).digest("hex");

function safeDemo(plan: GoalPlan): GoalPlan | null {
  try {
    const d = demoize(plan);
    return validatePlan(d, { demo: true }).ok ? d : null;
  } catch {
    return null;
  }
}

function fromPlan(base: ParseOutcome, plan: GoalPlan, templateId: string | null, source: ParseOutcome["source"], confidence: ParseOutcome["confidence"], notes: string[]): ParseOutcome {
  const extras = extrasFor(plan, templateId ?? undefined);
  return {
    ...base,
    status: "plan",
    source,
    plan,
    demoPlan: safeDemo(plan),
    templateId,
    trustTier: PROOF_TRUST[plan.proofMethods[0]!.type],
    needsPlace: extras.needsPlace,
    needsApp: extras.needsApp,
    limitations: extras.limitations,
    notes,
    confidence,
    clarifyingQuestions: plan.clarifyingQuestions,
  };
}

function option(label: string, plan: GoalPlan): PlanOption {
  return { label, plan, demoPlan: safeDemo(plan), extras: extrasFor(plan) };
}

function unverifiable(base: ParseOutcome, text: string, reason: string, alt: string, source: ParseOutcome["source"]): ParseOutcome {
  const self = validatePlan(selfReportAlternative(text));
  return {
    ...base,
    status: "unverifiable",
    source,
    reason: cleanText(reason, 300),
    suggestedAlternative: cleanText(alt, 300),
    alternatives: self.ok ? [option("Track it yourself with a daily confirmation (low trust, small stakes)", self.plan)] : [],
    confidence: "high",
  };
}

export async function parseGoal(s: Services, wallet: string, rawText: string, useAi: boolean): Promise<ParseOutcome> {
  const text = cleanText(rawText, MAX_GOAL_CHARS);
  const base = empty();
  if (text.length < 3) {
    return { ...base, clarifyingQuestions: ["What do you want to commit to? For example: do 20 squats every day."], examples: mixedExamples() };
  }
  // language that is not allowed in public goals is stopped here, before it can reach the matcher, the cache or the language model
  if (!checkText(text).ok) {
    return { ...base, reason: NOT_ALLOWED_MESSAGE, clarifyingQuestions: [NOT_ALLOWED_MESSAGE], examples: mixedExamples(), ai: { used: false, note: null } };
  }
  const cfg = s.config;
  const match = matchGoal(text);

  if (match?.kind === "unverifiable") return unverifiable(base, text, match.reason, match.suggestedAlternative, "template");
  if (match?.kind === "plan") {
    const v = validatePlan(match.plan);
    if (v.ok && match.confidence === "high" && cfg.GOALS_PARSER_MODE !== "llm_first") return fromPlan(base, v.plan, match.templateId, "template", "high", match.notes);
  }

  // the model, when it is allowed and within its limits
  let aiNote: string | null = null;
  const aiAllowed = useAi && cfg.GOALS_PARSER_MODE !== "template_only" && s.llm;
  if (aiAllowed) {
    const key = normalizeKey(text);
    const cached = s.db.prepare("SELECT result_json, created_at FROM goal_cache WHERE text_hash = ?").get(key) as { result_json: string; created_at: number } | undefined;
    if (cached && cached.created_at > s.wallNow() - CACHE_TTL_SEC) {
      const out = JSON.parse(cached.result_json) as ParseOutcome;
      return { ...out, source: "cache", ai: { used: false, note: "answered from an earlier result" } };
    }
    const limit = checkLlmLimits(s, wallet);
    if (limit) aiNote = limit;
    else {
      try {
        const raw = await s.llm!.parseGoal(text);
        const out = interpretModelReply(base, text, raw);
        if (out) {
          if (out.status !== "unclear") s.db.prepare("INSERT OR REPLACE INTO goal_cache (text_hash, result_json, created_at) VALUES (?,?,?)").run(key, JSON.stringify(out), s.wallNow());
          return out;
        }
        aiNote = "the language model's answer did not pass the safety checks, so it was not used";
      } catch (e) {
        if (e instanceof GeminiError) {
          aiNote =
            e.kind === "rate_limited" ? "the language model is busy right now"
            : e.kind === "blocked" ? "the language model is not reachable from this server"
            : e.kind === "network" || e.kind === "timeout" ? "the language model could not be reached right now"
            : "the language model gave no usable answer";
          if (e.kind === "rate_limited") s.db.prepare("INSERT OR REPLACE INTO kv (k, v) VALUES (?, ?)").run(BACKOFF_KEY, String(s.wallNow() + 60));
        } else aiNote = "the language model failed";
      }
    }
  } else if (!useAi) aiNote = "AI is switched off for this request";
  else if (!s.llm) aiNote = "no language model is configured on this server";

  // fall back to whatever the template matcher found, or ask
  if (match?.kind === "plan") {
    const v = validatePlan(match.plan);
    if (v.ok) return { ...fromPlan(base, v.plan, match.templateId, aiAllowed || aiNote ? "template-after-ai-failed" : "template", match.confidence, match.notes), ai: { used: false, note: aiNote } };
    return { ...base, reason: v.reason, clarifyingQuestions: [`That did not work as a goal: ${v.reason}. Try a different amount.`], examples: mixedExamples(), ai: { used: false, note: aiNote } };
  }
  return {
    ...base,
    clarifyingQuestions: ["I could not turn that into a goal a phone can check. How would you measure it each day (a number of reps, steps, minutes, or time away from an app)?"],
    examples: mixedExamples(),
    ai: { used: false, note: aiNote },
  };
}

function checkLlmLimits(s: Services, wallet: string): string | null {
  const backoff = s.db.prepare("SELECT v FROM kv WHERE k = ?").get(BACKOFF_KEY) as { v: string } | undefined;
  if (backoff && Number(backoff.v) > s.wallNow()) return "the language model is busy right now";
  const perWallet = hit(s, `wallet:${wallet}:llm-hour`, 3600);
  if (perWallet.count > s.config.GOALS_LLM_PER_WALLET_HOUR) throw tooMany(perWallet.retryAfter);
  const day = hit(s, "goals:llm-day", 86_400);
  if (day.count > s.config.GOALS_LLM_DAILY_CAP) return "today's allowance for the language model is used up";
  return null;
}

/** Turns the model's reply into an outcome, or null when it cannot be trusted. Never throws on bad content. */
export function interpretModelReply(base: ParseOutcome, text: string, raw: unknown): ParseOutcome | null {
  if (raw === null || typeof raw !== "object" || Array.isArray(raw)) return null;
  const o = raw as Record<string, unknown>;
  if (o.verifiable === false) {
    const reason = typeof o.unverifiableReason === "string" && o.unverifiableReason.trim() ? o.unverifiableReason : "a phone cannot verify this goal";
    const alt = typeof o.suggestedAlternative === "string" && o.suggestedAlternative.trim() ? o.suggestedAlternative : "Track it yourself with a daily confirmation (low trust, small stakes).";
    return { ...unverifiable(base, text, reason, alt, "ai"), ai: { used: true, note: null } };
  }
  const v = validatePlan(raw);
  if (!v.ok) return null;
  return { ...fromPlan(base, v.plan, null, "ai", "medium", v.notes), ai: { used: true, note: null } };
}
