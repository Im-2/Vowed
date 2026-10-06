/**
 * Day schedule of a challenge. Normal pools use real 24-hour days aligned to the participant's timezone.
 * DEMO POOLS use short "days" (60-3600 s) counted from the start time with no timezone, and a grace of a quarter day.
 * Both must equal programs/vowed/.../math.rs; shared vectors in /shared/test-vectors check this file against it.
 */
import { checkinWindowOk, dayIndex, windowBounds } from "./time.js";

/** The fields of a mirrored challenge that decide its schedule. SQLite stores booleans as 0/1. */
export interface ScheduleSource {
  start_ts: number;
  is_demo: number | boolean;
  day_secs: number;
}

export const DEMO_MIN_DAY_SECS = 60;
export const DEMO_MAX_DAY_SECS = 3_600;
export const DEMO_MAX_PARTICIPANTS = 20;

export const demoCheckinGrace = (daySecs: number): number => Math.floor(daySecs / 4);

export function demoDayIndex(now: number, startTs: number, daySecs: number): number {
  return Math.floor((now - startTs) / daySecs);
}

export function demoWindowBounds(startTs: number, daySecs: number, day: number): { opensAt: number; closesAt: number } {
  const opensAt = startTs + day * daySecs;
  return { opensAt, closesAt: opensAt + daySecs + demoCheckinGrace(daySecs) };
}

export function demoWindowOk(now: number, startTs: number, daySecs: number, day: number): boolean {
  const { opensAt, closesAt } = demoWindowBounds(startTs, daySecs, day);
  return now >= opensAt && now < closesAt;
}

export const isDemo = (c: ScheduleSource): boolean => c.is_demo === true || c.is_demo === 1;

export function windowFor(c: ScheduleSource, tzOffsetMinutes: number, day: number): { opensAt: number; closesAt: number } {
  return isDemo(c) ? demoWindowBounds(c.start_ts, c.day_secs, day) : windowBounds(c.start_ts, tzOffsetMinutes, day);
}

export function dayIndexFor(c: ScheduleSource, tzOffsetMinutes: number, now: number): number {
  return isDemo(c) ? demoDayIndex(now, c.start_ts, c.day_secs) : dayIndex(now, c.start_ts, tzOffsetMinutes);
}

export function windowOkFor(c: ScheduleSource, tzOffsetMinutes: number, day: number, now: number): boolean {
  return isDemo(c) ? demoWindowOk(now, c.start_ts, c.day_secs, day) : checkinWindowOk(now, c.start_ts, tzOffsetMinutes, day);
}

/** The plain-language label clients must show on demo pools. */
export function demoLabel(daySecs: number): string {
  return `DEMO POOL: each "day" lasts ${daySecs >= 120 ? `${Math.round(daySecs / 60)} minutes` : `${daySecs} seconds`}. For demonstration only, with test money.`;
}
