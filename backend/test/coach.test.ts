import { describe, expect, it } from "vitest";
import { MESSAGES, suggestFor, type CategoryHistory } from "../src/domain/coach.js";

const hist = (over: Partial<CategoryHistory> = {}): CategoryHistory => ({ category: "fitness", attemptedDays: 14, completedDays: 11, finished: [], proofHours: [], lastDifficulty: 3, ...over });

describe("adaptive coach (deterministic)", () => {
  it("says it needs more data below five attempted days", () => {
    expect(suggestFor(hist({ attemptedDays: 4, completedDays: 4 }))).toMatchObject({ action: "collect_more_data", reason: "NOT_ENOUGH_DATA", successRate: null });
  });

  it("suggests an easier next challenge below 70%", () => {
    const s = suggestFor(hist({ attemptedDays: 14, completedDays: 9, lastDifficulty: 4 })); // 64%
    expect(s).toMatchObject({ action: "easier", reason: "LOW_SUCCESS", suggestedDifficulty: 3, targetScale: 0.8 });
    expect(suggestFor(hist({ completedDays: 3, lastDifficulty: 1 })).suggestedDifficulty).toBe(1); // never below 1
  });

  it("keeps difficulty inside the 70-85% band (inclusive of both ends)", () => {
    expect(suggestFor(hist({ attemptedDays: 10, completedDays: 7 }))).toMatchObject({ action: "keep", reason: "IN_BAND" }); // exactly 70%
    expect(suggestFor(hist({ attemptedDays: 20, completedDays: 17 }))).toMatchObject({ action: "keep", reason: "IN_BAND" }); // exactly 85%
  });

  it("raises difficulty only after two strong cycles, not one", () => {
    const strong = { difficulty: 3, durationDays: 10, daysCompleted: 10 };
    const one = suggestFor(hist({ attemptedDays: 14, completedDays: 14, finished: [strong] }));
    expect(one).toMatchObject({ action: "keep", reason: "HIGH_SUCCESS_ONE_CYCLE" });
    const two = suggestFor(hist({ attemptedDays: 14, completedDays: 14, finished: [strong, strong], lastDifficulty: 5 }));
    expect(two).toMatchObject({ action: "harder", reason: "HIGH_SUCCESS_STREAK", suggestedDifficulty: 5, targetScale: 1.2 }); // capped at 5
    const mixed = suggestFor(hist({ attemptedDays: 14, completedDays: 14, finished: [strong, { ...strong, daysCompleted: 6 }] }));
    expect(mixed.action).toBe("keep");
  });

  it("flags late check-ins as a hint without changing the action", () => {
    const s = suggestFor(hist({ proofHours: [22, 23, 21, 22, 9] }));
    expect(s.hints).toContain("LATE_CHECKINS");
    expect(s.action).toBe("keep");
    expect(suggestFor(hist({ proofHours: [8, 9, 22] })).hints).toEqual([]);
  });

  it("only ever applies to the next challenge and has wording for every reason", () => {
    for (const h of [hist({ attemptedDays: 1 }), hist({ completedDays: 2 }), hist(), hist({ completedDays: 14 })]) {
      const s = suggestFor(h);
      expect(s.appliesTo).toBe("next_challenge");
      expect(MESSAGES[s.reason]).toBeTruthy();
    }
  });
});
