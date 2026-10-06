/**
 * Day-index and check-in window rules. Must match programs/vowed/programs/vowed/src/math.rs exactly:
 * both are tested against the same vectors in /shared/test-vectors/day-index.json.
 */
export const DAY_SECONDS = 86_400;
export const CHECKIN_GRACE_SECS = 7_200;

/** Local calendar day number (days since 1970-01-01 in the user's timezone). */
export function localDayNumber(ts: number, tzOffsetMinutes: number): number {
  return Math.floor((ts + tzOffsetMinutes * 60) / DAY_SECONDS);
}

/** Day index of `now` relative to the user's day 0 (the local day containing `startTs`). */
export function dayIndex(now: number, startTs: number, tzOffsetMinutes: number): number {
  return localDayNumber(now, tzOffsetMinutes) - localDayNumber(startTs, tzOffsetMinutes);
}

/** UTC instants (seconds) between which a check-in for `day` is accepted: [opensAt, closesAt). */
export function windowBounds(startTs: number, tzOffsetMinutes: number, day: number): { opensAt: number; closesAt: number } {
  const day0LocalStartUtc = localDayNumber(startTs, tzOffsetMinutes) * DAY_SECONDS - tzOffsetMinutes * 60;
  const opensAt = day0LocalStartUtc + day * DAY_SECONDS;
  return { opensAt, closesAt: opensAt + DAY_SECONDS + CHECKIN_GRACE_SECS };
}

export function checkinWindowOk(now: number, startTs: number, tzOffsetMinutes: number, day: number): boolean {
  const { opensAt, closesAt } = windowBounds(startTs, tzOffsetMinutes, day);
  return now >= opensAt && now < closesAt;
}

/** Number of consecutive completed days ending at the most recent day that is open or closed, counting back. */
export function currentStreak(bitmap: bigint, today: number, durationDays: number): number {
  let day = Math.min(today, durationDays - 1);
  // today may not be done yet: do not break the streak for it
  if (day >= 0 && ((bitmap >> BigInt(day)) & 1n) === 0n) day -= 1;
  let streak = 0;
  for (; day >= 0; day--) {
    if (((bitmap >> BigInt(day)) & 1n) === 1n) streak++;
    else break;
  }
  return streak;
}
