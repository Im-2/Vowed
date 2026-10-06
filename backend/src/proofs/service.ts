import { randomBytes, timingSafeEqual } from "node:crypto";
import { getChallenge, getParticipant, syncParticipation, syncPool } from "../challenges/sync.js";
import { GoalPlanSchema, minTier, PROOF_TRUST, type ProofType, type TrustTier } from "../domain/plan.js";
import { dayIndexFor, windowFor, windowOkFor } from "../domain/schedule.js";
import { currentStreak } from "../domain/time.js";
import { ApiError, conflict, forbidden, notFound } from "../errors.js";
import { submitCheckin, type CheckinOutcome } from "../oracle.js";
import type { Services } from "../services.js";
import { canonicalPackageBytes, evaluateProof, verifyDeviceSignature, type ProofPackage } from "./verify.js";

export const SESSION_TTL_SEC = 300;
const MAX_OPEN_SESSIONS = 5;
/** Evidence must have ended recently, and not in the future (clock skew allowance). */
const MAX_PROOF_AGE_SEC = 900;
const MAX_FUTURE_SKEW_SEC = 120;

export interface SessionInfo {
  sessionId: string;
  nonce: string;
  expiresAt: number;
  proofType: ProofType;
  dayIndex: number;
  /** What the plan asks for, so the app can show it. */
  target: { metric: string; value: number; unit: string; direction: "atLeast" | "atMost" };
  window: { opensAt: number; closesAt: number };
  /** true for a DEMO POOL (short days); show the demo label in the UI. */
  isDemo: boolean;
  daySecs: number;
}

export interface SubmitResult {
  accepted: true;
  trustTier: TrustTier;
  daysCompleted: number;
  streak: number;
  checkin: CheckinOutcome;
}

function loadPlan(planJson: string | null) {
  if (!planJson) throw conflict("plan_missing", "this challenge has no verified goal plan");
  return GoalPlanSchema.parse(JSON.parse(planJson));
}

async function ensureParticipant(s: Services, pool: string, wallet: string) {
  let p = getParticipant(s, pool, wallet);
  if (!p) {
    await syncParticipation(s, pool, wallet);
    p = getParticipant(s, pool, wallet);
  }
  if (!p) throw forbidden("not_participant", "you have not joined this challenge");
  return p;
}

export async function createSession(s: Services, wallet: string, pool: string, day: number, type: ProofType): Promise<SessionInfo> {
  await syncPool(s, pool);
  const c = getChallenge(s, pool);
  if (!c) throw notFound("challenge");
  if (c.status !== "Open") throw conflict("challenge_closed", `challenge is ${c.status}`);
  const plan = loadPlan(c.plan_json);
  const p = await ensureParticipant(s, pool, wallet);
  if (p.status !== "Active") throw conflict("participation_inactive", `participation is ${p.status}`);
  if (day < 0 || day >= c.duration_days) throw new ApiError(400, "day_out_of_range", "day is outside this challenge");
  const now = s.now();
  if (!windowOkFor(c, p.tz_offset_minutes, day, now)) throw conflict("window_closed", "the check-in window for that day is not open");
  if (((BigInt(p.checkin_bitmap) >> BigInt(day)) & 1n) === 1n) throw conflict("already_recorded", "that day is already recorded");
  if (!plan.proofMethods.some((m) => m.type === type)) throw new ApiError(400, "proof_type_not_in_plan", "this proof type is not part of the goal's plan");

  const device = s.db.prepare("SELECT 1 FROM devices WHERE id = ? AND wallet = ?").get(p.device_key_hash, wallet);
  if (!device) throw conflict("device_not_registered", "register the device you joined with before proving");

  s.db.prepare("DELETE FROM proof_sessions WHERE status = 'open' AND expires_at < ?").run(s.wallNow() - 3600);
  const open = s.db.prepare("SELECT COUNT(*) AS n FROM proof_sessions WHERE wallet = ? AND status = 'open' AND expires_at >= ?").get(wallet, s.wallNow()) as { n: number };
  if (open.n >= MAX_OPEN_SESSIONS) throw new ApiError(429, "too_many_sessions", "too many open proof sessions");

  const sessionId = randomBytes(16).toString("hex");
  const nonce = randomBytes(16).toString("hex");
  const expiresAt = s.wallNow() + SESSION_TTL_SEC;
  s.db
    .prepare("INSERT INTO proof_sessions (id, wallet, pool, day_index, proof_type, nonce, expires_at, status, created_at) VALUES (?,?,?,?,?,?,?, 'open', ?)")
    .run(sessionId, wallet, pool, day, type, nonce, expiresAt, s.wallNow());
  return { sessionId, nonce, expiresAt, proofType: type, dayIndex: day, target: plan.target, window: windowFor(c, p.tz_offset_minutes, day), isDemo: c.is_demo === 1, daySecs: c.day_secs };
}


function reject(s: Services, sessionId: string, pkg: ProofPackage, wallet: string, reason: string, tier: TrustTier = "low"): never {
  s.db.prepare("UPDATE proof_sessions SET status='rejected', result_json=? WHERE id=? AND status IN ('open','processing')").run(JSON.stringify({ reason }), sessionId);
  s.db
    .prepare(
      `INSERT OR IGNORE INTO proofs (session_id, wallet, pool, day_index, proof_type, trust_tier, evidence_hash, status, reject_reason, hour_of_day, created_at)
       VALUES (?,?,?,?,?,?,?, 'rejected', ?, NULL, ?)`,
    )
    .run(sessionId, wallet, pkg.challengeId, pkg.dayIndex, pkg.proofType, tier, pkg.evidenceHash, reason, s.wallNow());
  throw new ApiError(422, "proof_rejected", reason);
}

const same = (a: string, b: string) => a.length === b.length && timingSafeEqual(Buffer.from(a), Buffer.from(b));

export async function submitProof(s: Services, wallet: string, pkg: ProofPackage): Promise<SubmitResult> {
  try {
    return await submitProofInner(s, wallet, pkg);
  } catch (e) {
    // An unexpected failure (not a deliberate rejection) must not burn the session: reopen it so the app can retry.
    if (!(e instanceof ApiError)) s.db.prepare("UPDATE proof_sessions SET status='open' WHERE id=? AND status='processing'").run(pkg.sessionId);
    throw e;
  }
}

async function submitProofInner(s: Services, wallet: string, pkg: ProofPackage): Promise<SubmitResult> {
  const session = s.db.prepare("SELECT * FROM proof_sessions WHERE id = ?").get(pkg.sessionId) as
    | { id: string; wallet: string; pool: string; day_index: number; proof_type: string; nonce: string; expires_at: number; status: string; result_json: string | null }
    | undefined;
  if (!session || session.wallet !== wallet) throw notFound("proof session");

  // Replays and retries of an accepted session return the stored result; they never create a second check-in.
  if (session.status === "passed" && session.result_json) {
    const stored = JSON.parse(session.result_json) as SubmitResult;
    if (stored.checkin.status === "pending") stored.checkin = await submitCheckin(s, session.pool, wallet, session.day_index);
    return stored;
  }
  if (session.status === "processing") throw conflict("session_busy", "this proof is already being processed");
  if (session.status !== "open") throw conflict("session_closed", "this proof session is already closed; request a new one");
  if (session.expires_at < s.wallNow()) {
    s.db.prepare("UPDATE proof_sessions SET status='rejected', result_json=? WHERE id=?").run(JSON.stringify({ reason: "expired" }), session.id);
    throw conflict("session_expired", "the proof session expired; request a new one");
  }
  // Claim the session atomically: of two concurrent submissions only one proceeds.
  const claimed = s.db.prepare("UPDATE proof_sessions SET status='processing' WHERE id = ? AND status = 'open'").run(session.id);
  if (Number(claimed.changes) !== 1) throw conflict("session_busy", "this proof is already being processed");
  // Binding: the package must be for exactly this session.
  if (!same(pkg.nonce, session.nonce) || pkg.challengeId !== session.pool || pkg.dayIndex !== session.day_index || pkg.proofType !== session.proof_type) {
    return reject(s, session.id, pkg, wallet, "package does not match the proof session");
  }

  const c = getChallenge(s, session.pool);
  if (!c) throw notFound("challenge");
  const plan = loadPlan(c.plan_json);
  const p = await ensureParticipant(s, session.pool, wallet);

  // Device: the stake committed to this key at join time, and it must be registered to this wallet.
  if (pkg.deviceKeyId !== p.device_key_hash) return reject(s, session.id, pkg, wallet, "proof was signed by a different device than the one joined with");
  const device = s.db.prepare("SELECT pubkey, trust_cap FROM devices WHERE id = ? AND wallet = ?").get(pkg.deviceKeyId, wallet) as
    | { pubkey: Uint8Array; trust_cap: TrustTier }
    | undefined;
  if (!device) return reject(s, session.id, pkg, wallet, "device is not registered");
  if (!verifyDeviceSignature(device.pubkey, canonicalPackageBytes(pkg), pkg.signature)) return reject(s, session.id, pkg, wallet, "device signature is invalid");

  // Time: evidence for this day, recent, not from the future.
  const now = s.now();
  const { opensAt, closesAt } = windowFor(c, p.tz_offset_minutes, pkg.dayIndex);
  if (pkg.endedAt < pkg.startedAt) return reject(s, session.id, pkg, wallet, "proof ends before it starts");
  if (pkg.endedAt > now + MAX_FUTURE_SKEW_SEC) return reject(s, session.id, pkg, wallet, "proof is timestamped in the future");
  if (now - pkg.endedAt > MAX_PROOF_AGE_SEC) return reject(s, session.id, pkg, wallet, "proof is too old");
  if (pkg.endedAt < opensAt || pkg.endedAt >= closesAt) return reject(s, session.id, pkg, wallet, "proof is outside the day's window");
  if (!windowOkFor(c, p.tz_offset_minutes, pkg.dayIndex, now)) return reject(s, session.id, pkg, wallet, "the check-in window for that day has closed");
  if (pkg.startedAt < opensAt - Math.min(43_200, c.day_secs)) return reject(s, session.id, pkg, wallet, "proof starts too long before the day");

  const metric = evaluateProof(plan, pkg.proofType, pkg.metrics, pkg.startedAt, pkg.endedAt);
  if (!metric.ok) return reject(s, session.id, pkg, wallet, metric.reason);

  const trustTier = minTier(PROOF_TRUST[pkg.proofType], device.trust_cap);
  const hour = new Date((pkg.endedAt + p.tz_offset_minutes * 60) * 1000).getUTCHours();
  s.db
    .prepare(
      `INSERT INTO proofs (session_id, wallet, pool, day_index, proof_type, trust_tier, evidence_hash, status, hour_of_day, created_at)
       VALUES (?,?,?,?,?,?,?, 'accepted', ?, ?)`,
    )
    .run(session.id, wallet, session.pool, session.day_index, pkg.proofType, trustTier, pkg.evidenceHash, hour, s.wallNow());

  const checkin = await submitCheckin(s, session.pool, wallet, session.day_index);
  await syncParticipation(s, session.pool, wallet);
  const after = getParticipant(s, session.pool, wallet)!;
  const today = dayIndexFor(c, p.tz_offset_minutes, now);
  const result: SubmitResult = {
    accepted: true,
    trustTier,
    daysCompleted: after.days_completed,
    streak: currentStreak(BigInt(after.checkin_bitmap), today, c.duration_days),
    checkin,
  };
  s.db.prepare("UPDATE proof_sessions SET status='passed', result_json=? WHERE id=?").run(JSON.stringify(result), session.id);
  return result;
}
