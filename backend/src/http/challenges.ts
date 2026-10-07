import { planProblemForStake } from "../goals/validate.js";
import { assertPublicAllowed, recordMeta } from "../explore/service.js";
import { randomBytes } from "node:crypto";
import { PublicKey } from "@solana/web3.js";
import type { FastifyInstance } from "fastify";
import type { ZodTypeProvider } from "fastify-type-provider-zod";
import { z } from "zod";
import { buildTransaction } from "../chain/tx.js";
import { getChallenge, getParticipant, syncParticipation, syncPool, type ChallengeRow, type ParticipantRow } from "../challenges/sync.js";
import { claimable } from "../domain/payout.js";
import { DEMO_MAX_DAY_SECS, DEMO_MAX_PARTICIPANTS, DEMO_MIN_DAY_SECS, demoLabel } from "../domain/schedule.js";
import { canonicalJson, GoalPlanSchema, maxStakeForPlan, planHash, planTrustTier, type GoalPlan } from "../domain/plan.js";
import { ApiError, badRequest, conflict, forbidden, notFound } from "../errors.js";
import { processLogs } from "../indexer.js";
import type { ConfigAccount } from "../program/client.js";
import type { Services } from "../services.js";
import { ataAddress, ixCreateAtaIdempotent, tokenAmount } from "../util/token.js";
import { authenticate, pubkeySchema } from "./auth.js";
import { idempotent } from "./idempotency.js";
import { enforce } from "./ratelimit.js";

const u64 = z.string().regex(/^\d{1,20}$/);

export const TxResponse = z.object({
  /** base64 of the unsigned transaction. The wallet signs and sends it; the app must decode and check it first. */
  transaction: z.string(),
  blockhash: z.string(),
  lastValidBlockHeight: z.number(),
  pool: z.string(),
  summary: z.record(z.string(), z.union([z.string(), z.number(), z.boolean()])),
});

const configCache = new WeakMap<Services, { at: number; cfg: ConfigAccount }>();
/** Program config, cached for 30 seconds per Services instance. */
export async function getProgramConfig(s: Services): Promise<ConfigAccount> {
  const hit = configCache.get(s);
  if (hit && Date.now() - hit.at < 30_000) return hit.cfg;
  const acc = await s.chain.getAccount(s.program.configPda().toBase58());
  if (!acc) throw new ApiError(503, "program_not_initialised", "the program config account does not exist on this cluster");
  const cfg = s.program.decodeConfig(acc.data);
  configCache.set(s, { at: Date.now(), cfg });
  return cfg;
}

function serializeTx(tx: Awaited<ReturnType<typeof buildTransaction>>): string {
  return tx.serialize({ requireAllSignatures: false, verifySignatures: false }).toString("base64");
}

export function challengeView(c: ChallengeRow) {
  const plan = c.plan_json ? (GoalPlanSchema.safeParse(JSON.parse(c.plan_json)).data ?? null) : null;
  return {
    pool: c.pool,
    creator: c.creator,
    mint: c.mint,
    vault: c.vault,
    kind: c.kind,
    mode: c.mode,
    penaltyBps: c.penalty_bps,
    feeBps: c.fee_bps,
    startTs: c.start_ts,
    endTs: c.end_ts,
    joinDeadlineTs: c.join_deadline_ts,
    settleAfterTs: c.settle_after_ts,
    durationDays: c.duration_days,
    requiredDays: c.required_days,
    goalHash: c.goal_hash,
    participantCount: c.participant_count,
    maxParticipants: c.max_participants,
    settledCount: c.settled_count,
    pendingClaims: c.pending_claims,
    totalDeposits: c.total_deposits,
    totalForfeit: c.total_forfeit,
    totalSuccessStake: c.total_success_stake,
    distributable: c.distributable,
    status: c.status,
    squadId: c.squad_id,
    /** true for a DEMO POOL (minutes-long days, test money only). Clients must show demoLabel prominently. */
    isDemo: c.is_demo === 1,
    daySecs: c.day_secs,
    demoLabel: c.is_demo === 1 ? demoLabel(c.day_secs) : null,
    trustTier: plan ? planTrustTier(plan) : null,
    plan,
  };
}

const challengeSchema = z.object({
  pool: z.string(),
  creator: z.string(),
  mint: z.string(),
  vault: z.string(),
  kind: z.string(),
  mode: z.string(),
  penaltyBps: z.number(),
  feeBps: z.number(),
  startTs: z.number(),
  endTs: z.number(),
  joinDeadlineTs: z.number(),
  settleAfterTs: z.number(),
  durationDays: z.number(),
  requiredDays: z.number(),
  goalHash: z.string(),
  participantCount: z.number(),
  maxParticipants: z.number(),
  settledCount: z.number(),
  pendingClaims: z.number(),
  totalDeposits: z.string(),
  totalForfeit: z.string(),
  totalSuccessStake: z.string(),
  distributable: z.string(),
  status: z.string(),
  squadId: z.string().nullable(),
  isDemo: z.boolean(),
  daySecs: z.number(),
  demoLabel: z.string().nullable(),
  trustTier: z.enum(["high", "medium", "low"]).nullable(),
  plan: z.any().nullable(),
});

const participantView = (p: ParticipantRow) => ({
  wallet: p.wallet,
  stake: p.stake,
  tzOffsetMinutes: p.tz_offset_minutes,
  daysCompleted: p.days_completed,
  checkinBitmap: p.checkin_bitmap,
  status: p.status,
});

export function registerChallengeRoutes(app: FastifyInstance, s: Services) {
  const r = app.withTypeProvider<ZodTypeProvider>();
  const auth = authenticate(s);

  r.get(
    "/v1/meta",
    {
      schema: {
        tags: ["meta"],
        summary: "Program and deployment facts the app needs (no secrets)",
        response: {
          200: z.object({
            programId: z.string(),
            network: z.string(),
            authDomain: z.string(),
            initialised: z.boolean(),
            config: z
              .object({
                oracle: z.string(),
                treasury: z.string(),
                feeBps: z.number(),
                paused: z.boolean(),
                maxStake: z.string(),
                settleGraceSecs: z.string(),
                allowedMints: z.array(z.string()),
                demoEnabled: z.boolean(),
                demoMaxStake: z.string(),
                demoMints: z.array(z.string()),
              })
              .nullable(),
          }),
        },
      },
    },
    async () => {
      const acc = await s.chain.getAccount(s.program.configPda().toBase58());
      const cfg = acc ? s.program.decodeConfig(acc.data) : null;
      return {
        programId: s.program.programId.toBase58(),
        network: s.config.NETWORK,
        authDomain: s.config.AUTH_DOMAIN,
        initialised: !!cfg,
        config: cfg && {
          oracle: cfg.oracle,
          treasury: cfg.treasury,
          feeBps: cfg.fee_bps,
          paused: cfg.paused,
          maxStake: cfg.max_stake.toString(),
          settleGraceSecs: cfg.settle_grace_secs.toString(),
          allowedMints: cfg.allowed_mints.slice(0, cfg.allowed_mint_count),
          demoEnabled: cfg.demo_enabled,
          demoMaxStake: cfg.demo_max_stake.toString(),
          demoMints: cfg.allowed_mints.slice(0, cfg.allowed_mint_count).filter((_, i) => cfg.demo_mints[i]),
        },
      };
    },
  );

  r.get(
    "/v1/challenges",
    {
      preHandler: auth,
      schema: {
        tags: ["challenges"],
        summary: "List challenges I may see: public Open pools plus my own and my squads' (mine=true: only those I joined or created). Squad pools are unlisted: anyone holding the pool address can still fetch it by address, so invitations work.",
        querystring: z.object({
          status: z.enum(["Open", "Settling", "Settled", "Voided"]).optional(),
          mine: z.enum(["true", "false"]).default("false"),
          limit: z.coerce.number().int().min(1).max(100).default(50),
        }),
        response: { 200: z.object({ challenges: z.array(challengeSchema) }) },
      },
    },
    async (req) => {
      const { status, mine, limit } = req.query;
      const wallet = req.wallet!;
      const rows = s.db
        .prepare(
          `SELECT c.* FROM challenges c
           WHERE (?1 IS NULL OR c.status = ?1)
             AND (?2 = 'false' OR c.creator = ?3 OR EXISTS (SELECT 1 FROM participants p WHERE p.pool = c.pool AND p.wallet = ?3))
             -- Open pools are public. A Squad pool (and its goal text) is listed only to its creator, participants and squad members.
             AND (c.kind = 'Open' OR c.creator = ?3
                  OR EXISTS (SELECT 1 FROM participants p WHERE p.pool = c.pool AND p.wallet = ?3)
                  OR (c.squad_id IS NOT NULL AND EXISTS (SELECT 1 FROM squad_members m WHERE m.squad_id = c.squad_id AND m.wallet = ?3)))
           ORDER BY c.start_ts DESC LIMIT ?4`,
        )
        .all(status ?? null, mine, wallet, limit) as unknown as ChallengeRow[];
      return { challenges: rows.map(challengeView) };
    },
  );

  r.get(
    "/v1/challenges/:pool",
    {
      preHandler: auth,
      schema: {
        tags: ["challenges"],
        summary: "One challenge with its participants and my claimable amount",
        params: z.object({ pool: pubkeySchema }),
        response: {
          200: z.object({
            challenge: challengeSchema,
            participants: z.array(z.object({ wallet: z.string(), stake: z.string(), tzOffsetMinutes: z.number(), daysCompleted: z.number(), checkinBitmap: z.string(), status: z.string() })),
            me: z.object({ joined: z.boolean(), claimable: z.string() }),
          }),
        },
      },
    },
    async (req) => {
      const c = getChallenge(s, req.params.pool);
      if (!c) throw notFound("challenge");
      const parts = s.db.prepare("SELECT * FROM participants WHERE pool = ? ORDER BY days_completed DESC, wallet").all(c.pool) as unknown as ParticipantRow[];
      const mine = parts.find((p) => p.wallet === req.wallet);
      const amount = mine
        ? claimable({
            poolStatus: c.status,
            participantStatus: mine.status,
            stake: BigInt(mine.stake),
            penaltyBps: c.penalty_bps,
            distributable: BigInt(c.distributable),
            totalSuccessStake: BigInt(c.total_success_stake),
          })
        : 0n;
      return { challenge: challengeView(c), participants: parts.map(participantView), me: { joined: !!mine, claimable: amount.toString() } };
    },
  );

  // ---------------------------------------------------------------- transaction builders (unsigned)

  r.post(
    "/v1/challenges/tx/create",
    {
      preHandler: auth,
      schema: {
        tags: ["challenges"],
        summary: "Build the unsigned create-pool transaction for a goal plan (send an Idempotency-Key header to make retries safe)",
        body: z.object({
          mint: pubkeySchema,
          /** Default: Open for a public challenge, Squad for a private one. A public challenge must be Open. */
          kind: z.enum(["Squad", "Open"]).optional(),
          /** "public" lists the challenge in Explore (Open pools only); "private" (default) keeps it unlisted. Squad challenges are always private. */
          visibility: z.enum(["public", "private"]).default("private"),
          mode: z.enum(["Soft", "Hard"]),
          /** Soft mode only: 1-5000. Hard is always 10000. */
          penaltyBps: z.number().int().min(1).max(10_000).optional(),
          startTs: z.number().int().positive(),
          /** Default: one hour for normal pools, one demo day for demo pools. */
          joinWindowSecs: z.number().int().min(0).max(86_400).optional(),
          /** Default: 50 (demo pools: 10, and never more than 20). */
          maxParticipants: z.number().int().min(1).max(1_000).optional(),
          /**
           * Makes this a DEMO POOL: each "day" lasts daySecs seconds (60-3600) instead of 24 hours. Test money only; allowed only for
           * tokens and while the program config has demo pools enabled, with a lower stake cap. Normal pools omit this.
           */
          demo: z.object({ daySecs: z.number().int().min(DEMO_MIN_DAY_SECS).max(DEMO_MAX_DAY_SECS) }).optional(),
          plan: GoalPlanSchema,
          poolId: u64.optional(),
        }),
        response: { 200: TxResponse },
      },
    },
    async (req) => {
      const wallet = req.wallet!;
      enforce(s, `wallet:${wallet}:txbuild`, 30, 60);
      return idempotent(s, req, "tx/create", async () => {
        const b = req.body;
        const plan = b.plan;
        if (!plan.verifiable) throw badRequest("plan_not_verifiable", plan.unverifiableReason ?? "this goal cannot be verified");
        const planProblem = planProblemForStake(plan, { demo: !!b.demo });
        if (planProblem) throw badRequest("plan_invalid", `this goal plan cannot be staked on: ${planProblem}`);
        const kind = b.kind ?? (b.visibility === "public" ? "Open" : "Squad");
        if (b.visibility === "public") assertPublicAllowed(s, wallet, plan, kind);
        const cfg = await getProgramConfig(s);
        if (cfg.paused) throw conflict("paused", "the program is paused");
        if (!cfg.allowed_mints.slice(0, cfg.allowed_mint_count).includes(b.mint)) throw badRequest("mint_not_allowed", "that token is not enabled");
        const penaltyBps = b.mode === "Hard" ? 10_000 : b.penaltyBps;
        if (b.mode === "Soft" && (penaltyBps === undefined || penaltyBps > 5_000)) throw badRequest("bad_penalty", "soft mode needs penaltyBps between 1 and 5000");
        const minLead = b.demo ? 45 : 120;
        if (b.startTs < s.now() + minLead) throw badRequest("start_too_soon", `start time must be at least ${minLead} seconds ahead so there is time to sign`);
        const joinWindowSecs = b.joinWindowSecs ?? (b.demo ? b.demo.daySecs : 3_600);
        const maxParticipants = b.maxParticipants ?? (b.demo ? 10 : 50);
        if (b.demo) {
          // The program enforces all of this too; checking here gives clear errors before anyone signs.
          if (!cfg.demo_enabled) throw conflict("demo_not_enabled", "demo pools are switched off on this deployment");
          const idx = cfg.allowed_mints.slice(0, cfg.allowed_mint_count).indexOf(b.mint);
          if (!cfg.demo_mints[idx]) throw badRequest("demo_mint_not_allowed", "that token cannot be used for demo pools");
          if (maxParticipants > DEMO_MAX_PARTICIPANTS) throw badRequest("demo_too_many_participants", `demo pools allow at most ${DEMO_MAX_PARTICIPANTS} participants`);
          if (joinWindowSecs > b.demo.daySecs) throw badRequest("demo_join_window", "a demo pool's join window cannot exceed one demo day");
          if (b.startTs > s.now() + 86_400) throw badRequest("demo_start_too_far", "a demo pool must start within 24 hours");
        }

        const poolId = b.poolId ? BigInt(b.poolId) : BigInt(`0x${randomBytes(8).toString("hex")}`);
        const goalHash = planHash(plan);
        const creator = new PublicKey(wallet);
        const ix = s.program.ixCreatePool(creator, new PublicKey(b.mint), {
          pool_id: poolId,
          kind,
          mode: b.mode,
          penalty_bps: penaltyBps!,
          start_ts: BigInt(b.startTs),
          duration_days: plan.cadence.totalDays,
          required_days: plan.cadence.requiredDays,
          goal_hash: Buffer.from(goalHash, "hex"),
          join_window_secs: BigInt(joinWindowSecs),
          max_participants: maxParticipants,
          demo_day_secs: b.demo?.daySecs ?? 0,
        });
        s.db.prepare("INSERT OR IGNORE INTO plans (goal_hash, creator, plan_json, created_at) VALUES (?,?,?,?)").run(goalHash, wallet, canonicalJson(plan), s.wallNow());
        const tx = await buildTransaction(s.chain, creator, [ix]);
        const pool = s.program.poolPda(creator, poolId).toBase58();
        recordMeta(s, pool, wallet, b.visibility, plan.title, plan.category, plan.proofMethods[0]!.type);
        return {
          transaction: serializeTx(tx),
          blockhash: tx.recentBlockhash!,
          lastValidBlockHeight: tx.lastValidBlockHeight!,
          pool,
          summary: {
            action: "create_pool",
            pool,
            vault: s.program.vaultPda(pool).toBase58(),
            poolId: poolId.toString(),
            mint: b.mint,
            mode: b.mode,
            penaltyBps: penaltyBps!,
            startTs: b.startTs,
            durationDays: plan.cadence.totalDays,
            requiredDays: plan.cadence.requiredDays,
            goalHash,
            trustTier: planTrustTier(plan),
            maxStake: (b.demo && cfg.demo_max_stake < maxStakeForPlan(plan, cfg.max_stake) ? cfg.demo_max_stake : maxStakeForPlan(plan, cfg.max_stake)).toString(),
            isDemo: !!b.demo,
            kind,
            visibility: b.visibility,
            daySecs: b.demo?.daySecs ?? 86_400,
            ...(b.demo ? { label: demoLabel(b.demo.daySecs) } : {}),
          },
        };
      });
    },
  );

  r.post(
    "/v1/challenges/tx/join",
    {
      preHandler: auth,
      schema: {
        tags: ["challenges"],
        summary: "Build the unsigned join transaction (commits the stake to the device key you registered)",
        body: z.object({
          pool: pubkeySchema,
          stake: u64,
          tzOffsetMinutes: z.number().int().min(-720).max(840),
          /** hex sha256 of the device key, from /devices/register */
          deviceId: z.string().regex(/^[0-9a-f]{64}$/),
        }),
        response: { 200: TxResponse },
      },
    },
    async (req) => {
      const wallet = req.wallet!;
      enforce(s, `wallet:${wallet}:txbuild`, 30, 60);
      return idempotent(s, req, "tx/join", async () => {
        const b = req.body;
        await syncPool(s, b.pool);
        const c = getChallenge(s, b.pool);
        if (!c) throw notFound("challenge");
        if (c.status !== "Open") throw conflict("challenge_closed", `challenge is ${c.status}`);
        if (s.now() > c.join_deadline_ts) throw conflict("join_closed", "the join window has closed");
        if (c.participant_count >= c.max_participants) throw conflict("pool_full", "the challenge is full");
        if (!c.plan_json) throw conflict("plan_missing", "this challenge has no verified goal plan, so it cannot be proven");
        if (getParticipant(s, b.pool, wallet)) throw conflict("already_joined", "you already joined this challenge");
        if (c.squad_id && c.creator !== wallet && !s.db.prepare("SELECT 1 FROM squad_members WHERE squad_id = ? AND wallet = ?").get(c.squad_id, wallet)) {
          throw forbidden("not_in_squad", "this challenge belongs to a squad you are not a member of");
        }

        const plan = GoalPlanSchema.parse(JSON.parse(c.plan_json)) as GoalPlan;
        const cfg = await getProgramConfig(s);
        if (cfg.paused) throw conflict("paused", "the program is paused");
        const stake = BigInt(b.stake);
        let cap = maxStakeForPlan(plan, cfg.max_stake);
        if (c.is_demo === 1 && cfg.demo_max_stake < cap) cap = cfg.demo_max_stake; // demo pools have their own, lower cap
        if (stake <= 0n) throw badRequest("stake_zero", "stake must be greater than zero");
        if (stake > cap) throw badRequest("stake_over_cap", `stake is capped at ${cap} for a ${planTrustTier(plan)}-trust goal`, { cap: cap.toString() });

        const device = s.db.prepare("SELECT trust_cap FROM devices WHERE id = ? AND wallet = ?").get(b.deviceId, wallet);
        if (!device) throw conflict("device_not_registered", "register this device before joining");

        const user = new PublicKey(wallet);
        const mint = new PublicKey(c.mint);
        const userToken = ataAddress(user, mint);
        const tokenAcc = await s.chain.getAccount(userToken.toBase58());
        if (!tokenAcc || tokenAmount(tokenAcc.data) < stake) throw conflict("insufficient_funds", "your token account does not hold enough for this stake");

        const ix = s.program.ixJoinPool(user, { key: new PublicKey(c.pool), mint, vault: new PublicKey(c.vault) }, userToken, stake, b.tzOffsetMinutes, Buffer.from(b.deviceId, "hex"));
        const tx = await buildTransaction(s.chain, user, [ix]);
        return {
          transaction: serializeTx(tx),
          blockhash: tx.recentBlockhash!,
          lastValidBlockHeight: tx.lastValidBlockHeight!,
          pool: c.pool,
          summary: { action: "join_pool", pool: c.pool, vault: c.vault, mint: c.mint, stake: b.stake, tzOffsetMinutes: b.tzOffsetMinutes, deviceId: b.deviceId, mode: c.mode, penaltyBps: c.penalty_bps, isDemo: c.is_demo === 1, daySecs: c.day_secs, ...(c.is_demo === 1 ? { label: demoLabel(c.day_secs) } : {}) },
        };
      });
    },
  );

  r.post(
    "/v1/challenges/tx/claim",
    {
      preHandler: auth,
      schema: {
        tags: ["challenges"],
        summary: "Build the unsigned claim transaction for a settled or voided challenge",
        body: z.object({ pool: pubkeySchema }),
        response: { 200: TxResponse },
      },
    },
    async (req) => {
      const wallet = req.wallet!;
      enforce(s, `wallet:${wallet}:txbuild`, 30, 60);
      return idempotent(s, req, "tx/claim", async () => {
        const pool = req.body.pool;
        await syncPool(s, pool);
        await syncParticipation(s, pool, wallet);
        const c = getChallenge(s, pool);
        const p = getParticipant(s, pool, wallet);
        if (!c) throw notFound("challenge");
        if (!p) throw forbidden("not_participant", "you did not join this challenge");
        const amount = claimable({
          poolStatus: c.status,
          participantStatus: p.status,
          stake: BigInt(p.stake),
          penaltyBps: c.penalty_bps,
          distributable: BigInt(c.distributable),
          totalSuccessStake: BigInt(c.total_success_stake),
        });
        if (p.status === "Claimed") throw conflict("already_claimed", "already claimed");
        if (c.status !== "Settled" && c.status !== "Voided") throw conflict("not_settled", "the challenge is not settled yet");
        if (amount === 0n) throw conflict("nothing_to_claim", "nothing to claim for this challenge");

        const owner = new PublicKey(wallet);
        const mint = new PublicKey(c.mint);
        const ownerToken = ataAddress(owner, mint);
        const ixs = [
          ixCreateAtaIdempotent(owner, owner, mint),
          s.program.ixClaim(owner, { key: new PublicKey(c.pool), mint, vault: new PublicKey(c.vault) }, ownerToken),
        ];
        const tx = await buildTransaction(s.chain, owner, ixs);
        return {
          transaction: serializeTx(tx),
          blockhash: tx.recentBlockhash!,
          lastValidBlockHeight: tx.lastValidBlockHeight!,
          pool,
          summary: { action: "claim", pool, mint: c.mint, expectedAmount: amount.toString() },
        };
      });
    },
  );

  r.post(
    "/v1/challenges/sync",
    {
      preHandler: auth,
      schema: {
        tags: ["challenges"],
        summary: "Tell the backend about a transaction you sent so the mirror updates immediately (it re-reads the chain; nothing is trusted from the request)",
        body: z.object({ signature: z.string().min(80).max(100) }),
        response: { 200: z.object({ events: z.number() }) },
      },
    },
    async (req) => {
      enforce(s, `wallet:${req.wallet}:sync`, 30, 60);
      const logs = await s.chain.getTransactionLogs(req.body.signature);
      if (!logs) throw notFound("transaction");
      return { events: await processLogs(s, req.body.signature, logs) };
    },
  );
}
