/** The backend's time and payout rules must equal the program's: all three implementations are checked against /shared/test-vectors. */
import { existsSync, readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { claimable, failedRefund, penalty, splitForfeit, successPayout } from "../src/domain/payout.js";
import { CHECKIN_GRACE_SECS, checkinWindowOk, currentStreak, dayIndex, DAY_SECONDS, windowBounds } from "../src/domain/time.js";

function vector(name: string) {
  const candidates = [process.env.VOWED_SHARED && `${process.env.VOWED_SHARED}/test-vectors/${name}`, new URL(`../../shared/test-vectors/${name}`, import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, "$1")].filter(Boolean) as string[];
  const path = candidates.find((p) => existsSync(p));
  if (!path) throw new Error(`vector ${name} not found in ${candidates.join(", ")}`);
  return JSON.parse(readFileSync(path, "utf8"));
}

describe("shared day-index vectors", () => {
  const v = vector("day-index.json");
  it("constants match", () => {
    expect(v.checkin_grace_secs).toBe(CHECKIN_GRACE_SECS);
    expect(v.day_seconds).toBe(DAY_SECONDS);
  });
  it("day index", () => {
    expect(v.day_index.length).toBeGreaterThan(100);
    for (const c of v.day_index) expect(dayIndex(c.now, c.start_ts, c.tz), JSON.stringify(c)).toBe(c.day_index);
  });
  it("check-in window", () => {
    expect(v.window.length).toBeGreaterThan(100);
    for (const c of v.window) expect(checkinWindowOk(c.now, c.start_ts, c.tz, c.day), JSON.stringify(c)).toBe(c.ok);
  });
  it("windowBounds agrees with checkinWindowOk at the edges", () => {
    const { opensAt, closesAt } = windowBounds(1_799_971_200, -300, 4);
    expect(checkinWindowOk(opensAt - 1, 1_799_971_200, -300, 4)).toBe(false);
    expect(checkinWindowOk(opensAt, 1_799_971_200, -300, 4)).toBe(true);
    expect(checkinWindowOk(closesAt - 1, 1_799_971_200, -300, 4)).toBe(true);
    expect(checkinWindowOk(closesAt, 1_799_971_200, -300, 4)).toBe(false);
  });
});

describe("shared payout vectors", () => {
  const v = vector("payout.json");
  it("matches every case", () => {
    expect(v.cases.length).toBeGreaterThanOrEqual(50);
    for (const c of v.cases) {
      const stakes = c.stakes.map(BigInt) as bigint[];
      const forfeit = stakes.reduce((acc: bigint, s: bigint, i: number) => acc + (c.succeeded[i] ? 0n : penalty(s, c.penalty_bps)), 0n);
      const { fee, distributable } = splitForfeit(forfeit, c.fee_bps);
      const succ = stakes.reduce((acc: bigint, s: bigint, i: number) => acc + (c.succeeded[i] ? s : 0n), 0n);
      const pays = stakes.map((s: bigint, i: number) => (c.succeeded[i] ? successPayout(s, distributable, succ) : failedRefund(s, c.penalty_bps)));
      expect(pays.map(String), c.name).toEqual(c.payouts);
      expect(String(fee), c.name).toBe(String(c.fee));
      expect(String(distributable), c.name).toBe(String(c.distributable));
      const total = stakes.reduce((a: bigint, b: bigint) => a + b, 0n);
      expect(String(total - pays.reduce((a: bigint, b: bigint) => a + b, 0n)), c.name).toBe(String(c.treasury));
    }
  });
  it("claimable respects status", () => {
    const base = { stake: 100n, penaltyBps: 10_000, distributable: 50n, totalSuccessStake: 100n };
    expect(claimable({ ...base, poolStatus: "Settled", participantStatus: "Succeeded" })).toBe(150n);
    expect(claimable({ ...base, poolStatus: "Settled", participantStatus: "Failed" })).toBe(0n);
    expect(claimable({ ...base, poolStatus: "Settled", participantStatus: "Claimed" })).toBe(0n);
    expect(claimable({ ...base, poolStatus: "Settling", participantStatus: "Succeeded" })).toBe(0n);
    expect(claimable({ ...base, poolStatus: "Voided", participantStatus: "Failed" })).toBe(100n);
    expect(claimable({ ...base, poolStatus: "Open", participantStatus: "Active" })).toBe(0n);
  });
});

describe("streak", () => {
  it("counts consecutive completed days and does not break on an unfinished today", () => {
    expect(currentStreak(0b0111n, 2, 7)).toBe(3); // days 0,1,2 done, today = 2
    expect(currentStreak(0b0011n, 2, 7)).toBe(2); // today (2) not done yet: streak so far 2
    expect(currentStreak(0b1011n, 3, 7)).toBe(1); // gap at day 2, today 3 done
    expect(currentStreak(0b0001n, 5, 7)).toBe(0); // missed yesterday and today
    expect(currentStreak(0n, 0, 7)).toBe(0);
    expect(currentStreak(0b1111111n, 20, 7)).toBe(7); // after the end, capped at the last day
  });
});
