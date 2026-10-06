/**
 * Adaptive difficulty coach (SPEC 9.3): deterministic rules, no AI. It only ever suggests settings for the NEXT challenge
 * and never changes an active one. Target success band: 70-85%.
 */
export const BAND_LOW = 0.7;
export const BAND_HIGH = 0.85;
export const WINDOW_DAYS = 14;
export const MIN_DAYS_FOR_ADVICE = 5;

export interface CategoryHistory {
  category: string;
  /** Days whose check-in window closed inside the last 14 days, and how many of them were completed. */
  attemptedDays: number;
  completedDays: number;
  /** Most recent finished challenges in this category, newest first. */
  finished: { difficulty: number; durationDays: number; daysCompleted: number }[];
  /** Local hours (0-23) of accepted proofs in the window. */
  proofHours: number[];
  /** Difficulty of the most recent challenge (finished or running); 3 if unknown. */
  lastDifficulty: number;
}

export type ReasonCode = "NOT_ENOUGH_DATA" | "LOW_SUCCESS" | "IN_BAND" | "HIGH_SUCCESS_ONE_CYCLE" | "HIGH_SUCCESS_STREAK";
export type Action = "collect_more_data" | "easier" | "keep" | "harder";

export interface Suggestion {
  category: string;
  action: Action;
  reason: ReasonCode;
  successRate: number | null;
  suggestedDifficulty: number | null;
  /** Multiply the target value by this for the next challenge (e.g. 0.8 = 20% easier). */
  targetScale: number;
  hints: string[];
  appliesTo: "next_challenge";
}

const clampDifficulty = (n: number) => Math.min(5, Math.max(1, n));

export function suggestFor(h: CategoryHistory): Suggestion {
  const base = { category: h.category, appliesTo: "next_challenge" as const, hints: [] as string[] };
  if (h.proofHours.length >= 3 && h.proofHours.filter((x) => x >= 21).length / h.proofHours.length >= 0.6) base.hints.push("LATE_CHECKINS");

  if (h.attemptedDays < MIN_DAYS_FOR_ADVICE) {
    return { ...base, action: "collect_more_data", reason: "NOT_ENOUGH_DATA", successRate: null, suggestedDifficulty: null, targetScale: 1 };
  }
  const rate = h.completedDays / h.attemptedDays;
  if (rate < BAND_LOW) {
    return { ...base, action: "easier", reason: "LOW_SUCCESS", successRate: rate, suggestedDifficulty: clampDifficulty(h.lastDifficulty - 1), targetScale: 0.8 };
  }
  if (rate > BAND_HIGH) {
    const lastTwo = h.finished.slice(0, 2);
    const twoCycles = lastTwo.length === 2 && lastTwo.every((f) => f.daysCompleted / f.durationDays > BAND_HIGH);
    if (twoCycles) {
      return { ...base, action: "harder", reason: "HIGH_SUCCESS_STREAK", successRate: rate, suggestedDifficulty: clampDifficulty(h.lastDifficulty + 1), targetScale: 1.2 };
    }
    return { ...base, action: "keep", reason: "HIGH_SUCCESS_ONE_CYCLE", successRate: rate, suggestedDifficulty: h.lastDifficulty, targetScale: 1 };
  }
  return { ...base, action: "keep", reason: "IN_BAND", successRate: rate, suggestedDifficulty: h.lastDifficulty, targetScale: 1 };
}

/** Wording is by reason code; clients localise from string resources. This default is only for the API. */
export const MESSAGES: Record<ReasonCode, string> = {
  NOT_ENOUGH_DATA: "Not enough recent days yet to give advice. Keep checking in.",
  LOW_SUCCESS: "You completed fewer than 70% of recent days. Try a smaller target or a shorter challenge next time.",
  IN_BAND: "Your recent success rate is in the healthy range. Keep this difficulty.",
  HIGH_SUCCESS_ONE_CYCLE: "Strong run. One more strong challenge and we will suggest raising the difficulty.",
  HIGH_SUCCESS_STREAK: "You finished two challenges above 85%. Time to raise the difficulty a notch.",
};
