//! Pure, side-effect-free helpers. Everything here is covered by unit tests and by the shared
//! test vectors in `/shared/test-vectors`, which the backend and the Android app reuse.

use crate::constants::*;

/// Local calendar day number (days since 1970-01-01 in the user's timezone).
pub fn local_day_number(ts: i64, tz_offset_minutes: i16) -> i64 {
    (ts + tz_offset_minutes as i64 * 60).div_euclid(SECONDS_PER_DAY)
}

/// Day index of `now` relative to the user's day 0 (the local day containing `start_ts`).
/// Negative before day 0.
pub fn day_index(now: i64, start_ts: i64, tz_offset_minutes: i16) -> i64 {
    local_day_number(now, tz_offset_minutes) - local_day_number(start_ts, tz_offset_minutes)
}

/// Is `now` inside the check-in window for `day`?
/// Window = the user's local day `day`, extended by CHECKIN_GRACE_SECS after it ends.
pub fn checkin_window_ok(now: i64, start_ts: i64, tz_offset_minutes: i16, day: u8) -> bool {
    let local_now = now + tz_offset_minutes as i64 * 60;
    let day0_start = local_day_number(start_ts, tz_offset_minutes) * SECONDS_PER_DAY;
    let window_start = day0_start + day as i64 * SECONDS_PER_DAY;
    local_now >= window_start && local_now < window_start + SECONDS_PER_DAY + CHECKIN_GRACE_SECS
}

/// Demo pools: check-in grace is a quarter of the (short) day instead of 2 hours.
pub fn demo_checkin_grace(day_secs: u32) -> i64 {
    (day_secs / 4) as i64
}

/// Demo pools count days from the start time (no timezone): day d is [start + d*day_secs, start + (d+1)*day_secs).
pub fn demo_day_index(now: i64, start_ts: i64, day_secs: u32) -> i64 {
    (now - start_ts).div_euclid(day_secs as i64)
}

/// Is `now` inside the check-in window for `day` of a demo pool? The window is the demo day plus its grace.
pub fn demo_window_ok(now: i64, start_ts: i64, day_secs: u32, day: u8) -> bool {
    let opens = start_ts + day as i64 * day_secs as i64;
    now >= opens && now < opens + day_secs as i64 + demo_checkin_grace(day_secs)
}

/// amount * bps / 10_000, rounded down, using u128 intermediates.
pub fn bps_of(amount: u64, bps: u16) -> Option<u64> {
    let v = (amount as u128).checked_mul(bps as u128)? / BPS_DENOMINATOR as u128;
    u64::try_from(v).ok()
}

pub fn penalty(stake: u64, penalty_bps: u16) -> Option<u64> {
    bps_of(stake, penalty_bps)
}

/// What a Failed participant gets back.
pub fn failed_refund(stake: u64, penalty_bps: u16) -> Option<u64> {
    stake.checked_sub(penalty(stake, penalty_bps)?)
}

/// fee = F * fee_bps / 10_000 ; D = F - fee. Returns (fee, distributable).
pub fn split_forfeit(total_forfeit: u64, fee_bps: u16) -> Option<(u64, u64)> {
    let fee = bps_of(total_forfeit, fee_bps)?;
    Some((fee, total_forfeit.checked_sub(fee)?))
}

/// stake_j + D * stake_j / S (rounded down). `total_success_stake` must be >= `stake` and > 0.
pub fn success_payout(stake: u64, distributable: u64, total_success_stake: u64) -> Option<u64> {
    if total_success_stake == 0 || stake > total_success_stake {
        return None;
    }
    let share = (distributable as u128).checked_mul(stake as u128)? / total_success_stake as u128;
    stake.checked_add(u64::try_from(share).ok()?)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn day_index_basic() {
        assert_eq!(day_index(86_400, 86_400, 0), 0);
        assert_eq!(day_index(86_400 + 86_399, 86_400, 0), 0);
        assert_eq!(day_index(2 * 86_400, 86_400, 0), 1);
        assert_eq!(day_index(86_399, 86_400, 0), -1);
    }

    #[test]
    fn day_index_timezones() {
        let start = 86_400; // 00:00 UTC on 1970-01-02
        // For a UTC+2 user that instant is 02:00 local on 1970-01-02: still local day 1.
        assert_eq!(local_day_number(start, 120), 1);
        // 23:00 UTC the same day is already local day 2 for UTC+2, still day 1 for UTC.
        assert_eq!(day_index(start + 23 * 3_600, start, 0), 0);
        assert_eq!(day_index(start + 23 * 3_600, start, 120), 1);
        // UTC-5: start instant is 19:00 the previous local day.
        assert_eq!(local_day_number(start, -300), 0);
    }

    #[test]
    fn window_has_grace() {
        let start = 0;
        assert!(checkin_window_ok(0, start, 0, 0));
        assert!(checkin_window_ok(SECONDS_PER_DAY - 1, start, 0, 0));
        assert!(checkin_window_ok(SECONDS_PER_DAY + CHECKIN_GRACE_SECS - 1, start, 0, 0));
        assert!(!checkin_window_ok(SECONDS_PER_DAY + CHECKIN_GRACE_SECS, start, 0, 0));
        assert!(!checkin_window_ok(SECONDS_PER_DAY - 1, start, 0, 1));
        assert!(checkin_window_ok(SECONDS_PER_DAY, start, 0, 1));
    }

    #[test]
    fn demo_windows() {
        // 60-second days starting at t=1000
        assert_eq!(demo_day_index(1000, 1000, 60), 0);
        assert_eq!(demo_day_index(1059, 1000, 60), 0);
        assert_eq!(demo_day_index(1060, 1000, 60), 1);
        assert_eq!(demo_day_index(999, 1000, 60), -1);
        assert_eq!(demo_checkin_grace(60), 15);
        assert!(!demo_window_ok(999, 1000, 60, 0));
        assert!(demo_window_ok(1000, 1000, 60, 0));
        assert!(demo_window_ok(1000 + 60 + 14, 1000, 60, 0)); // grace
        assert!(!demo_window_ok(1000 + 60 + 15, 1000, 60, 0));
        assert!(!demo_window_ok(1059, 1000, 60, 1)); // day 1 not open yet
        assert!(demo_window_ok(1060, 1000, 60, 1));
    }

    #[test]
    fn payout_math() {
        assert_eq!(penalty(1_000, 5_000), Some(500));
        assert_eq!(failed_refund(1_000, 10_000), Some(0));
        assert_eq!(split_forfeit(1_000, 250), Some((25, 975)));
        assert_eq!(success_payout(100, 1_000, 400), Some(350));
        assert_eq!(success_payout(300, 1_000, 400), Some(1_050));
        // rounding down: D=10, stakes 1 and 2 of 3 -> 3 and 6 extra, dust 1
        assert_eq!(success_payout(1, 10, 3), Some(4));
        assert_eq!(success_payout(2, 10, 3), Some(8));
        assert_eq!(success_payout(1, 0, 0), None);
        assert!(success_payout(u64::MAX / 2, u64::MAX / 2, u64::MAX / 2).is_some());
    }
}
