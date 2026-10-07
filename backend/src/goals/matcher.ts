/**
 * Deterministic goal parser: no AI, no network. It maps a typed goal to one of the catalog templates, reads the amount, the app, the time
 * window and the length from the text, and recognises goals that no phone can verify. It is the fallback for the language model and
 * also answers the common goals by itself, which saves free-tier quota.
 */
import { buildFromTemplate, CATALOG, templateById, type BuildOptions, type CatalogTemplate } from "./catalog.js";
import { cleanText } from "./validate.js";
import type { GoalPlan } from "../domain/plan.js";

export const KNOWN_APPS = ["TikTok", "Instagram", "YouTube", "Twitter", "X", "Facebook", "Snapchat", "Reddit", "Netflix", "Twitch", "Discord", "WhatsApp", "Telegram", "Pinterest", "LinkedIn", "Threads", "Steam", "Roblox"];
const APP_PATTERN = new RegExp(`\\b(${KNOWN_APPS.filter((a) => a !== "X").join("|")})\\b`, "i");

const WORD_NUMBERS: Record<string, number> = { a: 1, an: 1, one: 1, two: 2, three: 3, four: 4, five: 5, six: 6, seven: 7, eight: 8, nine: 9, ten: 10, eleven: 11, twelve: 12, fifteen: 15, twenty: 20, thirty: 30, forty: 40, fifty: 50, sixty: 60, hundred: 100 };

export interface Hints {
  /** every number in the text with the word that follows it */
  numbers: { value: number; next: string }[];
  /** "10pm" or "22:00" times, in order of appearance */
  times: string[];
  app?: string;
  days?: number;
}

const norm = (s: string) => s.toLowerCase().replace(/[’']/g, "'").replace(/\s+/g, " ").trim();

export function extractHints(text: string): Hints {
  const t = norm(text);
  const numbers: Hints["numbers"] = [];
  // times first, so "10pm" is not read as the number 10
  const times: string[] = [];
  const timeRe = /\b(\d{1,2})(?::(\d{2}))?\s*(am|pm)\b|\b([01]?\d|2[0-3]):([0-5]\d)\b/g;
  let stripped = t;
  for (const m of t.matchAll(timeRe)) {
    let h: number;
    let min: number;
    if (m[3]) {
      h = Number(m[1]) % 12 + (m[3] === "pm" ? 12 : 0);
      min = Number(m[2] ?? 0);
    } else {
      h = Number(m[4]);
      min = Number(m[5]);
    }
    if (h <= 23 && min <= 59) times.push(`${String(h).padStart(2, "0")}:${String(min).padStart(2, "0")}`);
    stripped = stripped.replace(m[0], " ");
  }
  const numRe = /(\d[\d,]*(?:\.\d+)?)\s*(k\b)?\s*([a-z-]+)?|\b(a|an|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|twenty|thirty|forty|fifty|sixty|hundred)\b\s+([a-z-]+)?/g;
  for (const m of stripped.matchAll(numRe)) {
    if (m[1] !== undefined) {
      const raw = Number(m[1].replace(/,/g, ""));
      if (Number.isFinite(raw)) numbers.push({ value: m[2] ? raw * 1000 : raw, next: m[3] ?? "" });
    } else if (m[4] && !(m[4] === "a" || m[4] === "an") ) {
      numbers.push({ value: WORD_NUMBERS[m[4]]!, next: m[5] ?? "" });
    } else if (m[4] && m[5] && /^(hour|hours|minute|minutes|min|mins|day|days|week|weeks|month|months)$/.test(m[5])) {
      numbers.push({ value: 1, next: m[5] });
    }
  }
  if (/\bhalf an hour\b|\b30 mins?\b/.test(t) && !numbers.some((n) => /min|hour/.test(n.next))) numbers.push({ value: 30, next: "minutes" });
  const appMatch = text.match(APP_PATTERN);
  const app = appMatch ? KNOWN_APPS.find((a) => a.toLowerCase() === appMatch[1]!.toLowerCase()) : /\bon x\b|\bx app\b/.test(t) ? "X" : undefined;
  return { numbers, times, app, days: extractDays(t, numbers) };
}

function extractDays(t: string, numbers: Hints["numbers"]): number | undefined {
  const m = t.match(/\bfor\s+(?:the\s+next\s+)?(\d+|a|one|two|three|four|five|six|seven|eight|nine|ten|twelve)\s+(day|days|week|weeks|month|months)\b/);
  if (m) {
    const n = /^\d+$/.test(m[1]!) ? Number(m[1]) : WORD_NUMBERS[m[1]!]!;
    const mult = m[2]!.startsWith("week") ? 7 : m[2]!.startsWith("month") ? 30 : 1;
    return Math.min(60, Math.max(1, n * mult));
  }
  if (/\ba week\b|\bweekly challenge\b|\bthis week\b/.test(t)) return 7;
  if (/\ba month\b|\bthis month\b/.test(t)) return 30;
  void numbers;
  return undefined;
}

// ---------------------------------------------------------------- goals nobody can verify

const UNVERIFIABLE: { re: RegExp; reason: string; alt: string }[] = [
  { re: /\b(lose|losing|drop|gain)\b.*\b(weight|kg|kgs|kilos?|lbs?|pounds?|fat)\b|\bweigh\b|\bbody fat\b/, reason: "A phone cannot weigh you, and weight changes over weeks do not depend on a single day.", alt: "Commit to the habit instead, for example walking 8,000 steps a day." },
  { re: /\b(eat|eating|diet|calories?|sugar|junk food|healthy food|fast food|snack|vegetables?)\b/, reason: "A phone cannot see what you eat.", alt: "Track it yourself with a daily confirmation (low trust, small stakes)." },
  { re: /\b(smoke|smoking|cigarettes?|vape|vaping|nicotine|alcohol|drinking beer|drink beer|wine|booze|sober)\b/, reason: "A phone cannot tell whether you smoked or drank.", alt: "Track it yourself with a daily confirmation (low trust, small stakes)." },
  { re: /\b(happy|happier|happiness|mood|anxious|anxiety|stress|stressed|confidence|motivated|motivation|nicer|kinder|patient|patience)\b/, reason: "Feelings cannot be measured by a phone.", alt: "Pick an action that supports it, such as meditating 10 minutes a day." },
  { re: /\b(save|saving|spend less|budget|money)\b/, reason: "A phone cannot see your bank account.", alt: "Track it yourself with a daily confirmation (low trust, small stakes)." },
  { re: /\b(sleep (better|well|quality)|good night'?s? sleep|8 hours of sleep|hours of sleep)\b/, reason: "Sleep quality and length cannot be read reliably from a phone.", alt: "Try a phone-free window overnight, which can be checked." },
  { re: /\b(call|text|message|visit|see)\b.*\b(mom|mum|dad|parents?|friends?|family|grandma|grandpa)\b/, reason: "A phone cannot tell who you spoke to.", alt: "Track it yourself with a daily confirmation (low trust, small stakes)." },
];

export type MatchResult =
  | { kind: "plan"; templateId: string; plan: GoalPlan; confidence: "high" | "medium"; notes: string[] }
  | { kind: "unverifiable"; reason: string; suggestedAlternative: string; templateId?: string };

const SELF_ATTEST_FALLBACK = (text: string): GoalPlan => ({
  title: cleanText(`Daily: ${text}`, 80) || "My daily goal",
  category: "custom",
  cadence: { periodDays: 1, totalDays: 7, requiredDays: 6 },
  target: { metric: "done", value: 1, unit: "times", direction: "atLeast" },
  proofMethods: [{ type: "SELF_ATTEST", params: {}, trustTier: "low" }],
  window: null,
  difficulty: 2,
  verifiable: true,
  unverifiableReason: null,
  suggestedAlternative: null,
  clarifyingQuestions: [],
});

/** A low-trust "I did it" version of a goal the phone cannot check. Shown as an option, never chosen silently. */
export const selfReportAlternative = (text: string): GoalPlan => SELF_ATTEST_FALLBACK(text);

// ---------------------------------------------------------------- picking a template

function phraseScore(t: string, words: string[]): number {
  let s = 0;
  for (const w of words) {
    const re = new RegExp(`(^|[^a-z0-9])${w.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}([^a-z0-9]|$)`);
    if (re.test(t)) s += w.length >= 8 ? 3 : w.length >= 5 ? 2 : 1;
  }
  return s;
}

const LIMIT_WORDS = /\b(less than|under|at most|no more than|max(imum)?|limit|cut down|reduce|only)\b/;
const BAN_WORDS = /\b(no|not|stop|quit|avoid|off|without|never|ban)\b/;

function pickTemplate(t: string, h: Hints): CatalogTemplate | undefined {
  // goals about a named app
  if (h.app || /\b(phone|social media|screen time|scrolling)\b/.test(t)) {
    const overnight = /\b(sleep|bed|bedtime|night|overnight)\b/.test(t) || (h.times.length >= 2 && h.times[0]! >= "20:00" && h.times[1]! <= "10:00");
    if (overnight && (BAN_WORDS.test(t) || h.times.length > 0)) return templateById("sleep-window");
    if (LIMIT_WORDS.test(t) && h.numbers.some((n) => /^(min|mins|minute|minutes|hour|hours|hr|hrs)$/.test(n.next))) return templateById("usage-limit");
    if (BAN_WORDS.test(t) || h.times.length > 0) return templateById("no-use-window");
    if (h.numbers.length > 0 && LIMIT_WORDS.test(t)) return templateById("usage-limit");
  }
  let best: CatalogTemplate | undefined;
  let bestScore = 0;
  for (const tpl of CATALOG) {
    if (tpl.id === "usage-limit" || tpl.id === "no-use-window" || tpl.id === "sleep-window") continue;
    let s = phraseScore(t, tpl.keywords);
    // weak, generic keywords only count when nothing more specific matched
    if (tpl.id === "gym" && !/\b(gym|fitness cent(er|re)|library|climbing|pool|studio|office)\b/.test(t)) s = 0;
    if (s > bestScore) {
      best = tpl;
      bestScore = s;
    }
  }
  return best;
}

/** First number whose following word is one of the template's units; else the first plausible number. */
function pickAmount(tpl: CatalogTemplate, h: Hints): { value: number; unit: string } | undefined {
  const timeUnit = (w: string): string | undefined => (/^(sec|secs|second|seconds)$/.test(w) ? "seconds" : /^(min|mins|minute|minutes)$/.test(w) ? "minutes" : /^(hr|hrs|hour|hours)$/.test(w) ? "hours" : undefined);
  const timed = ["FOCUS_TIMER", "GEOFENCE", "USAGE_LIMIT", "NO_USE_WINDOW"].includes(tpl.proofType);
  for (const n of h.numbers) {
    if (timed) {
      const u = timeUnit(n.next);
      if (u) return { value: n.value, unit: u };
    } else if (tpl.unitWords.includes(n.next) || tpl.unitWords.some((w) => n.next.startsWith(w))) {
      return { value: n.value, unit: tpl.unit };
    }
  }
  if (!timed) {
    // "walk 10000" or "do 20": a bare number that is not a duration
    const bare = h.numbers.find((n) => !/^(day|days|week|weeks|month|months|hour|hours|min|mins|minute|minutes)$/.test(n.next));
    if (bare && (tpl.proofType === "SELF_ATTEST" || bare.value >= tpl.range.min)) return { value: bare.value, unit: tpl.unit };
  }
  return undefined;
}

export function matchGoal(text: string): MatchResult | undefined {
  const t = norm(text);
  const h = extractHints(text);
  for (const u of UNVERIFIABLE) {
    if (u.re.test(t)) {
      // a goal with a clear checkable action beside the vague part ("walk 5000 steps to lose weight") is still checkable
      const concrete = pickTemplate(t, h);
      if (concrete && concrete.proofType !== "SELF_ATTEST" && (h.numbers.length > 0 || h.app)) break;
      return { kind: "unverifiable", reason: u.reason, suggestedAlternative: u.alt };
    }
  }
  const tpl = pickTemplate(t, h);
  if (!tpl) return undefined;

  const notes: string[] = [];
  const o: BuildOptions = {};
  if (tpl.needsApp) {
    o.app = h.app ?? tpl.proofParams.app;
    if (!h.app) notes.push(`Which app? I assumed ${o.app}; change it on the next screen.`);
  }
  if (tpl.id === "no-use-window") {
    if (h.times.length >= 2) o.window = { startLocalTime: h.times[0]!, endLocalTime: h.times[1]! };
    else if (h.times.length === 1) o.window = { startLocalTime: h.times[0]!, endLocalTime: "23:59" };
    else o.window = { startLocalTime: "00:00", endLocalTime: "23:59" };
  }
  if (tpl.id === "sleep-window" && h.times.length >= 2) o.window = { startLocalTime: h.times[0]!, endLocalTime: h.times[1]! };
  if (tpl.id === "sleep-window" && h.times.length === 1) o.window = { startLocalTime: h.times[0]!, endLocalTime: "06:00" };
  if (tpl.id === "early-wake" && h.times.length >= 1) {
    const end = h.times[h.times.length - 1]!;
    const hh = Number(end.slice(0, 2));
    o.window = { startLocalTime: `${String(Math.max(0, hh - 2)).padStart(2, "0")}:${end.slice(3)}`, endLocalTime: end };
  }
  let confidence: "high" | "medium" = "high";
  if (tpl.proofType !== "NO_USE_WINDOW" && tpl.id !== "early-wake" && tpl.id !== "sleep-window") {
    const amount = pickAmount(tpl, h);
    if (amount) {
      o.value = amount.value;
      o.unit = amount.unit;
    } else {
      confidence = "medium";
      notes.push(`I did not find an amount, so I used ${tpl.value} ${tpl.unit}; change it on the next screen.`);
    }
  }
  if (tpl.id === "usage-limit" && o.value === undefined) confidence = "medium";
  if (h.days) o.totalDays = h.days;
  if (tpl.proofType === "NO_USE_WINDOW") o.value = 0;
  try {
    return { kind: "plan", templateId: tpl.id, plan: buildFromTemplate(tpl, o), confidence, notes };
  } catch {
    return undefined;
  }
}
