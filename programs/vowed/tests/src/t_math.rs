//! Payout math and day-index rules: shared vectors, property tests, and a randomised on-chain run.
use crate::common::*;
use proptest::prelude::*;
use serde_json::Value;
use vowed::{math, state::StakeMode};

fn load_vector(name: &str) -> Value {
    let manifest = env!("CARGO_MANIFEST_DIR");
    // Work copy (scripts/program-build.sh syncs /shared next to the crate) or the repo layout.
    let candidates = [
        format!("{manifest}/../shared/test-vectors/{name}"),
        format!("{manifest}/../../../shared/test-vectors/{name}"),
        format!("{manifest}/../../../../shared/test-vectors/{name}"),
    ];
    for c in &candidates {
        if let Ok(s) = std::fs::read_to_string(c) {
            return serde_json::from_str(&s).unwrap();
        }
    }
    panic!("test vector {name} not found in {candidates:?}");
}

/// Amounts in the vectors are decimal strings (exact in every language); small fields may be plain numbers.
fn u(v: &Value) -> u64 {
    match v {
        Value::String(s) => s.parse().unwrap(),
        _ => v.as_u64().unwrap(),
    }
}
fn i(v: &Value) -> i64 {
    v.as_i64().unwrap()
}

#[test]
fn shared_vectors_day_index() {
    let v = load_vector("day-index.json");
    assert_eq!(i(&v["checkin_grace_secs"]), vowed::constants::CHECKIN_GRACE_SECS);
    assert_eq!(i(&v["day_seconds"]), vowed::constants::SECONDS_PER_DAY);
    let cases = v["day_index"].as_array().unwrap();
    assert!(cases.len() > 100);
    for c in cases {
        let got = math::day_index(i(&c["now"]), i(&c["start_ts"]), i(&c["tz"]) as i16);
        assert_eq!(got, i(&c["day_index"]), "day_index case {c}");
    }
}

#[test]
fn shared_vectors_checkin_window() {
    let v = load_vector("day-index.json");
    let cases = v["window"].as_array().unwrap();
    assert!(cases.len() > 100);
    for c in cases {
        let got = math::checkin_window_ok(i(&c["now"]), i(&c["start_ts"]), i(&c["tz"]) as i16, u(&c["day"]) as u8);
        assert_eq!(got, c["ok"].as_bool().unwrap(), "window case {c}");
    }
}

/// Reference settlement using only the program's math helpers (same order of operations as the program).
fn model(stakes: &[u64], ok: &[bool], penalty_bps: u16, fee_bps: u16) -> (Vec<u64>, u64, u64, u64) {
    let pen: Vec<u64> = stakes
        .iter()
        .zip(ok)
        .map(|(s, o)| if *o { 0 } else { math::penalty(*s, penalty_bps).unwrap() })
        .collect();
    let forfeit: u64 = pen.iter().sum();
    let (fee, dist) = math::split_forfeit(forfeit, fee_bps).unwrap();
    let succ: u64 = stakes.iter().zip(ok).filter(|(_, o)| **o).map(|(s, _)| *s).sum();
    let pays: Vec<u64> = stakes
        .iter()
        .zip(ok)
        .zip(&pen)
        .map(|((s, o), p)| if *o { math::success_payout(*s, dist, succ).unwrap() } else { s - p })
        .collect();
    let total: u128 = stakes.iter().map(|s| *s as u128).sum();
    let treasury = (total - pays.iter().map(|p| *p as u128).sum::<u128>()) as u64;
    (pays, treasury, fee, dist)
}

#[test]
fn shared_vectors_payouts() {
    let v = load_vector("payout.json");
    let cases = v["cases"].as_array().unwrap();
    assert!(cases.len() >= 50);
    for c in cases {
        let stakes: Vec<u64> = c["stakes"].as_array().unwrap().iter().map(u).collect();
        let ok: Vec<bool> = c["succeeded"].as_array().unwrap().iter().map(|b| b.as_bool().unwrap()).collect();
        let (pays, treasury, fee, dist) = model(&stakes, &ok, u(&c["penalty_bps"]) as u16, u(&c["fee_bps"]) as u16);
        let want: Vec<u64> = c["payouts"].as_array().unwrap().iter().map(u).collect();
        assert_eq!(pays, want, "payouts for {}", c["name"]);
        assert_eq!(treasury, u(&c["treasury"]), "treasury for {}", c["name"]);
        assert_eq!(fee, u(&c["fee"]), "fee for {}", c["name"]);
        assert_eq!(dist, u(&c["distributable"]), "distributable for {}", c["name"]);
    }
}

proptest! {
    /// Money is conserved and nobody is paid more than was deposited, for any stakes, outcomes and rates.
    #[test]
    fn payouts_never_exceed_deposits(
        stakes in prop::collection::vec(1u64..=u64::MAX / 16, 1..12),
        flags in prop::collection::vec(any::<bool>(), 12),
        penalty_bps in 1u16..=10_000,
        fee_bps in 0u16..=1_000,
    ) {
        let ok: Vec<bool> = flags[..stakes.len()].to_vec();
        let (pays, treasury, fee, dist) = model(&stakes, &ok, penalty_bps, fee_bps);
        let deposited: u128 = stakes.iter().map(|s| *s as u128).sum();
        let paid: u128 = pays.iter().map(|p| *p as u128).sum();
        prop_assert!(paid <= deposited);
        prop_assert_eq!(paid + treasury as u128, deposited);
        prop_assert!(treasury >= fee, "treasury gets at least the fee");
        // a winner never gets less than their stake; a loser never more
        for ((s, o), p) in stakes.iter().zip(&ok).zip(&pays) {
            if *o { prop_assert!(p >= s) } else { prop_assert!(p <= s) }
        }
        // no distributable money is created or lost
        let forfeit: u128 = stakes.iter().zip(&ok).filter(|(_, o)| !**o)
            .map(|(s, _)| math::penalty(*s, penalty_bps).unwrap() as u128).sum();
        prop_assert_eq!(forfeit, (fee + dist) as u128);
    }

    #[test]
    fn day_index_is_monotonic_and_matches_window(
        start in 0i64..4_000_000_000,
        delta in 0i64..(70 * 86_400),
        tz in -720i16..=840,
    ) {
        let now = start + delta;
        let d = math::day_index(now, start, tz);
        prop_assert!(d >= 0);
        prop_assert!(math::day_index(now + 1, start, tz) >= d);
        if (0..60).contains(&d) {
            prop_assert!(math::checkin_window_ok(now, start, tz, d as u8));
        }
        // the window never opens early
        if d >= 1 { prop_assert!(!math::checkin_window_ok(now, start, tz, (d + 1) as u8)); }
    }
}

proptest! {
    #![proptest_config(ProptestConfig::with_cases(24))]

    /// Full on-chain flow with random users: final balances equal the model, the vault ends empty,
    /// and total money (users + treasury) is exactly what was deposited.
    #[test]
    fn onchain_random_pools_conserve_money(
        stakes in prop::collection::vec(1u64..=1_000_000_000, 1..6),
        days in prop::collection::vec(0u8..=3, 6),
        soft in any::<bool>(),
        soft_bps in 1u16..=5_000,
        fee_bps in 0u16..=1_000,
    ) {
        let mut env = Env::with_fee(fee_bps);
        let (mode, penalty) = if soft { (StakeMode::Soft, soft_bps) } else { (StakeMode::Hard, 10_000) };
        let pool = env.pool(1, mode, penalty, 3, 2);
        let mut users = vec![];
        for s in &stakes {
            let user = env.new_user(*s);
            env.join(&pool, &user, *s, 0).unwrap();
            users.push(user);
        }
        for (idx, u) in users.iter().enumerate() {
            for d in 0..days[idx] {
                env.checkin_on_day(&pool, &u.key(), d).unwrap();
            }
        }
        let refs: Vec<&User> = users.iter().collect();
        env.settle_all(&pool, &refs);
        let ok: Vec<bool> = (0..stakes.len()).map(|i| days[i] >= 2).collect();
        let (want, want_treasury, _, _) = model(&stakes, &ok, penalty, fee_bps);

        for (idx, u) in users.iter().enumerate() {
            let before = env.bal(&u.token);
            let r = env.claim(&pool, u);
            if want[idx] == 0 {
                prop_assert!(failed_with(&r, "NothingToClaim"));
            } else {
                prop_assert!(r.is_ok());
            }
            prop_assert_eq!(env.bal(&u.token) - before, want[idx]);
        }
        let swept = env.sweep(&pool);
        if want_treasury == 0 {
            prop_assert!(failed_with(&swept, "NothingToSweep"));
        } else {
            prop_assert!(swept.is_ok());
        }
        prop_assert_eq!(env.bal(&env.treasury_token.clone()), want_treasury);
        prop_assert_eq!(env.bal(&pool.vault), 0);
        let deposited: u64 = stakes.iter().sum();
        prop_assert_eq!(env.total_in_users_and_treasury(&refs), deposited);
    }
}
