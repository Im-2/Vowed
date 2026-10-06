use anchor_lang::prelude::*;

#[constant]
pub const CONFIG_SEED: &[u8] = b"config";
#[constant]
pub const POOL_SEED: &[u8] = b"pool";
#[constant]
pub const VAULT_SEED: &[u8] = b"vault";
#[constant]
pub const PARTICIPATION_SEED: &[u8] = b"part";

pub const BPS_DENOMINATOR: u64 = 10_000;
/// Fee cap: 10%.
pub const MAX_FEE_BPS: u16 = 1_000;
/// Soft mode can forfeit at most half of the stake (SPEC 4.1).
pub const MAX_SOFT_PENALTY_BPS: u16 = 5_000;
pub const HARD_PENALTY_BPS: u16 = 10_000;
pub const MAX_DURATION_DAYS: u8 = 60;
pub const SECONDS_PER_DAY: i64 = 86_400;
/// Oracle may still record day d until this long after local day d ended (latency, retries).
pub const CHECKIN_GRACE_SECS: i64 = 7_200;
pub const MAX_JOIN_WINDOW_SECS: i64 = SECONDS_PER_DAY;
/// A pool may be scheduled at most this far ahead.
pub const MAX_START_AHEAD_SECS: i64 = 30 * SECONDS_PER_DAY;
/// Upper bound for config.settle_grace_secs.
pub const MAX_SETTLE_GRACE_SECS: i64 = 2 * SECONDS_PER_DAY;
/// UTC-12:00 .. UTC+14:00 in minutes.
pub const MIN_TZ_OFFSET_MINUTES: i16 = -720;
pub const MAX_TZ_OFFSET_MINUTES: i16 = 840;
pub const MAX_ALLOWED_MINTS: usize = 4;
pub const MAX_PARTICIPANTS_LIMIT: u32 = 1_000;
/// Normal pools: one real day.
pub const NORMAL_DAY_SECS: u32 = 86_400;
// ---- DEMO POOLS: minutes-long "days" for demonstrations and tests only. Everything else about a pool stays the same.
pub const DEMO_MIN_DAY_SECS: u32 = 60;
pub const DEMO_MAX_DAY_SECS: u32 = 3_600;
pub const DEMO_MAX_PARTICIPANTS: u32 = 20;
/// A demo pool must start within a day of creation (normal pools: 30 days).
pub const DEMO_MAX_START_AHEAD_SECS: i64 = SECONDS_PER_DAY;
/// Size of a plain SPL mint. Larger means Token-2022 extensions (transfer fees etc.), which we reject.
pub const PLAIN_MINT_LEN: usize = 82;
