//! DEMO POOLS: minutes-long "days" for demonstrations and tests. They must follow every rule normal pools follow, with only tighter limits.
use crate::common::*;
use solana_signer::Signer;
use vowed::instructions::CreatePoolParams;
use vowed::state::{PoolStatus, StakeMode};

const DEMO_START: i64 = BASE + 600;

fn demo_params(env: &Env, id: u64, day_secs: u32, duration: u8, required: u8) -> CreatePoolParams {
    let mut p = env.create_params(id, StakeMode::Hard, 10_000, duration, required);
    p.demo_day_secs = day_secs;
    p.start_ts = DEMO_START;
    p.join_window_secs = 30;
    p.max_participants = 10;
    p
}

fn demo_pool(env: &mut Env, id: u64, day_secs: u32, duration: u8, required: u8) -> PoolRef {
    let params = demo_params(env, id, day_secs, duration, required);
    let (r, pool) = env.create_pool_with(params);
    r.expect("create demo pool");
    pool
}

fn try_create(env: &mut Env, p: CreatePoolParams) -> litesvm::types::TransactionResult {
    env.create_pool_with(p).0
}

#[test]
fn demo_pool_is_labelled_on_chain_and_uses_short_days() {
    let mut env = Env::new();
    let pool = demo_pool(&mut env, 1, 60, 2, 2);
    let s = env.pool_state(&pool);
    assert!(s.is_demo, "the pool must be flagged as a demo pool");
    assert_eq!(s.day_secs, 60);
    assert_eq!(s.start_ts, DEMO_START);
    assert_eq!(s.end_ts, DEMO_START + 120);
    assert_eq!(s.join_deadline_ts, DEMO_START + 30);
    // settles one demo-day after the end, not after the 2-hour normal grace
    assert_eq!(s.settle_after_ts, DEMO_START + 120 + 60);
    assert_eq!(s.status, PoolStatus::Open);
    // a normal pool created in the same config is untouched
    let normal = env.hard_pool(2, 7, 5);
    let n = env.pool_state(&normal);
    assert!(!n.is_demo);
    assert_eq!(n.day_secs, 86_400);
    assert_eq!(n.settle_after_ts, n.end_ts + SETTLE_GRACE);
}

#[test]
fn demo_checkins_follow_short_days_and_a_quarter_day_grace() {
    let mut env = Env::new();
    let pool = demo_pool(&mut env, 1, 60, 2, 1);
    let alice = env.new_user(10 * UNIT);
    let bob = env.new_user(10 * UNIT);
    env.join(&pool, &alice, UNIT, 0).unwrap();
    env.join(&pool, &bob, UNIT, 0).unwrap();

    env.set_time(DEMO_START - 1);
    assert_err(env.checkin(&pool, &alice.key(), 0), "OutsideCheckinWindow"); // not started
    env.set_time(DEMO_START + 10);
    env.checkin(&pool, &alice.key(), 0).unwrap();
    assert_err(env.checkin(&pool, &alice.key(), 0), "DuplicateCheckin");
    assert_err(env.checkin(&pool, &alice.key(), 1), "OutsideCheckinWindow"); // day 1 opens at start + 60
    env.set_time(DEMO_START + 59);
    assert_err(env.checkin(&pool, &alice.key(), 1), "OutsideCheckinWindow");
    env.set_time(DEMO_START + 60);
    env.checkin(&pool, &alice.key(), 1).unwrap();
    // day 0 stays open for the 15-second grace after it ends, then closes
    env.set_time(DEMO_START + 60 + 14);
    env.checkin(&pool, &bob.key(), 0).unwrap();
    let carol = env.new_user(10 * UNIT);
    env.set_time(DEMO_START + 5);
    env.join(&pool, &carol, UNIT, 0).unwrap();
    env.set_time(DEMO_START + 60 + 15);
    assert_err(env.checkin(&pool, &carol.key(), 0), "OutsideCheckinWindow");
    // beyond the challenge length
    assert_err(env.checkin(&pool, &alice.key(), 2), "DayOutOfRange");
    assert_eq!(env.part_state(&pool, &alice.key()).days_completed, 2);
}

#[test]
fn demo_pool_settles_in_minutes_and_pays_out_exactly_like_a_normal_pool() {
    let mut env = Env::new();
    let pool = demo_pool(&mut env, 1, 60, 2, 2);
    let alice = env.new_user(10 * UNIT);
    let bob = env.new_user(10 * UNIT);
    let carol = env.new_user(10 * UNIT);
    for (u, s) in [(&alice, 4 * UNIT), (&bob, 2 * UNIT), (&carol, 2 * UNIT)] {
        env.join(&pool, u, s, 0).unwrap();
    }
    for d in 0..2u8 {
        env.checkin_on_day(&pool, &alice.key(), d).unwrap();
        env.checkin_on_day(&pool, &carol.key(), d).unwrap();
    }
    env.checkin_on_day(&pool, &bob.key(), 0).unwrap(); // bob finishes only one of the two required days

    env.set_time(pool.settle_after() - 1);
    assert_err(env.settle(&pool, &alice.key()), "NotEnded");
    assert!(pool.settle_after() - DEMO_START <= 180, "the whole demo takes about three minutes");
    env.settle_all(&pool, &[&alice, &bob, &carol]);
    assert_eq!(env.pool_state(&pool).status, PoolStatus::Settled);
    env.claim(&pool, &alice).unwrap();
    env.claim(&pool, &carol).unwrap();
    assert_err(env.claim(&pool, &bob), "NothingToClaim");
    // winners: stake + D*stake/S with S = 6, D = 2 (in UNIT), rounded down
    assert_eq!(env.bal(&alice.token), 10 * UNIT + 1_333_333); // kept 6, got 4 back plus 1.333333 of bob's stake
    assert_eq!(env.bal(&carol.token), 10 * UNIT + 666_666);
    env.sweep(&pool).unwrap();
    assert_eq!(env.bal(&env.treasury_token.clone()), 1); // rounding dust
    assert_eq!(env.bal(&pool.vault), 0);
    assert_eq!(env.total_in_users_and_treasury(&[&alice, &bob, &carol]), 30 * UNIT);
}

#[test]
fn demo_day_length_is_bounded() {
    let mut env = Env::new();
    for (i, secs) in [0u32 + 1, 59, 3_601, u32::MAX].into_iter().enumerate() {
        let p = demo_params(&env, 10 + i as u64, secs, 2, 1);
        assert_err(try_create(&mut env, p), "InvalidDemoDay");
    }
    let mut p = demo_params(&env, 20, 60, 2, 1);
    p.join_window_secs = 0;
    try_create(&mut env, p).unwrap(); // exactly 60 is allowed
    let mut p = demo_params(&env, 21, 3_600, 2, 1);
    p.join_window_secs = 3_600;
    try_create(&mut env, p).unwrap(); // exactly 3600 is allowed
}

#[test]
fn demo_pool_cannot_be_looser_than_a_normal_pool() {
    let mut env = Env::new();
    // every normal-pool rule still applies
    let mut p = demo_params(&env, 1, 60, 61, 5);
    assert_err(try_create(&mut env, p.clone()), "InvalidPoolParams"); // more than 60 days
    p.duration_days = 5;
    p.required_days = 6;
    assert_err(try_create(&mut env, p), "InvalidPoolParams");
    let mut p = demo_params(&env, 2, 60, 2, 1);
    p.penalty_bps = 9_999; // Hard must forfeit everything
    assert_err(try_create(&mut env, p), "InvalidPoolParams");
    let mut p = demo_params(&env, 3, 60, 2, 1);
    p.mode = StakeMode::Soft;
    p.penalty_bps = 5_001;
    assert_err(try_create(&mut env, p), "InvalidPoolParams");
    let mut p = demo_params(&env, 4, 60, 2, 1);
    p.start_ts = BASE; // not in the future
    assert_err(try_create(&mut env, p), "InvalidPoolParams");
    // and there are tighter ones: participants, start window, join window
    let mut p = demo_params(&env, 5, 60, 2, 1);
    p.max_participants = 21;
    assert_err(try_create(&mut env, p), "InvalidPoolParams");
    let mut p = demo_params(&env, 6, 60, 2, 1);
    p.max_participants = 20;
    try_create(&mut env, p).unwrap();
    let mut p = demo_params(&env, 7, 60, 2, 1);
    p.start_ts = BASE + DAY + 1; // a demo pool must start within a day
    assert_err(try_create(&mut env, p), "InvalidPoolParams");
    let mut p = demo_params(&env, 8, 60, 2, 1);
    p.start_ts = BASE + DAY;
    try_create(&mut env, p).unwrap();
    let mut p = demo_params(&env, 9, 60, 2, 1);
    p.join_window_secs = 61; // joins must close within one demo day
    assert_err(try_create(&mut env, p), "InvalidPoolParams");
    // a token that is not allowed at all is still refused first; one that is allowed for normal pools but not for demos is refused for demos
    let funder = env.funder.insecure_clone();
    for (mint, code) in [(env.third_mint, "MintNotAllowed"), (env.other_mint, "DemoMintNotAllowed")] {
        let ix = env.ix_create_pool(&pk_of(&funder), &mint, demo_params(&env, 30, 60, 2, 1));
        assert_err(env.send(ix, &[&funder]), code);
    }
    // the same token works for a NORMAL pool
    let ix = env.ix_create_pool(&pk_of(&funder), &env.other_mint.clone(), env.create_params(31, StakeMode::Hard, 10_000, 7, 5));
    env.send(ix, &[&funder]).unwrap();
    // the pause switch stops demo pools too
    env.set_paused(true);
    let p = demo_params(&env, 40, 60, 2, 1);
    assert_err(try_create(&mut env, p), "Paused");
}

#[test]
fn demo_stake_cap_is_lower_than_the_normal_cap() {
    let mut env = Env::new();
    let demo = demo_pool(&mut env, 1, 60, 2, 1);
    let normal = env.hard_pool(2, 7, 5);
    let u = env.new_user(100 * UNIT);
    // above the demo cap but far below the normal cap
    assert!(DEMO_MAX_STAKE + 1 < MAX_STAKE);
    assert_err(env.join(&demo, &u, DEMO_MAX_STAKE + 1, 0), "StakeTooLarge");
    env.join(&demo, &u, DEMO_MAX_STAKE, 0).unwrap();
    env.join(&normal, &u, DEMO_MAX_STAKE + 1, 0).unwrap(); // fine in a normal pool
    // the join window of a demo pool is short too
    let late = env.new_user(UNIT);
    env.set_time(DEMO_START + 31);
    assert_err(env.join(&demo, &late, UNIT, 0), "JoinClosed");
}

#[test]
fn demo_days_ignore_timezones() {
    let mut env = Env::new();
    let pool = demo_pool(&mut env, 1, 60, 2, 1);
    let east = env.new_user(10 * UNIT);
    let west = env.new_user(10 * UNIT);
    env.join(&pool, &east, UNIT, 840).unwrap();
    env.join(&pool, &west, UNIT, -720).unwrap();
    env.set_time(DEMO_START + 5);
    env.checkin(&pool, &east.key(), 0).unwrap();
    env.checkin(&pool, &west.key(), 0).unwrap();
    env.set_time(DEMO_START + 59);
    assert_err(env.checkin(&pool, &east.key(), 1), "OutsideCheckinWindow");
    assert_err(env.checkin(&pool, &west.key(), 1), "OutsideCheckinWindow");
    env.set_time(DEMO_START + 60);
    env.checkin(&pool, &east.key(), 1).unwrap();
    env.checkin(&pool, &west.key(), 1).unwrap();
}

#[test]
fn demo_creation_can_be_switched_off_without_stranding_existing_pools() {
    let mut env = Env::new();
    let pool = demo_pool(&mut env, 1, 60, 2, 1);
    let alice = env.new_user(10 * UNIT);
    env.join(&pool, &alice, UNIT, 0).unwrap();

    // only the admin may flip it
    let oracle = env.oracle.insecure_clone();
    let ix = env.ix_set_demo_enabled(&pk_of(&oracle), false);
    assert_err(env.send(ix, &[&oracle]), "Unauthorized");
    let admin = env.admin.insecure_clone();
    let ix = env.ix_set_demo_enabled(&pk_of(&admin), false);
    env.send(ix, &[&admin]).unwrap();
    assert!(!env.config_state().demo_enabled);

    let p = demo_params(&env, 2, 60, 2, 1);
    assert_err(try_create(&mut env, p), "DemoDisabled");
    env.hard_pool(3, 7, 5); // normal pools are unaffected

    // the existing demo pool still works end to end
    let bob = env.new_user(10 * UNIT);
    env.set_time(DEMO_START + 5);
    env.join(&pool, &bob, UNIT, 0).unwrap();
    env.checkin_on_day(&pool, &alice.key(), 0).unwrap();
    env.settle_all(&pool, &[&alice, &bob]);
    env.claim(&pool, &alice).unwrap();
    assert_eq!(env.bal(&alice.token), 11 * UNIT);

    let ix = env.ix_set_demo_enabled(&pk_of(&admin), true);
    env.send(ix, &[&admin]).unwrap();
    env.set_time(BASE); // the earlier steps moved the clock past this pool's start
    let p = demo_params(&env, 4, 60, 2, 1);
    try_create(&mut env, p).unwrap();
}

#[test]
fn demo_config_can_only_be_stricter_than_the_normal_config() {
    let mut env = Env::bare();
    let admin = env.admin.insecure_clone();
    let good = env.init_params(0);
    let try_init = |env: &mut Env, p: vowed::instructions::InitConfigParams| {
        let ix = env.ix_init_config(&pk_of(&admin), p);
        env.send(ix, &[&admin])
    };
    let mut p = good.clone();
    p.demo_max_stake = p.max_stake + 1; // demo cap above the normal cap
    assert_err(try_init(&mut env, p), "InvalidConfig");
    let mut p = good.clone();
    p.demo_mints = vec![env.third_mint]; // demo token that is not an allowed token
    assert_err(try_init(&mut env, p), "InvalidConfig");
    let mut p = good.clone();
    p.demo_max_stake = 0; // demo enabled but no cap
    assert_err(try_init(&mut env, p), "InvalidConfig");
    let mut p = good.clone();
    p.demo_mints = vec![]; // demo enabled but no demo token
    assert_err(try_init(&mut env, p), "InvalidConfig");
    // demo switched off is a valid configuration (what a real-money deployment should use)
    let mut off = good;
    off.demo_enabled = false;
    off.demo_max_stake = 0;
    off.demo_mints = vec![];
    try_init(&mut env, off).unwrap();
    assert!(!env.config_state().demo_enabled);
    // and it cannot be switched on afterwards without a cap and a demo token
    let ix = env.ix_set_demo_enabled(&pk_of(&admin), true);
    assert_err(env.send(ix, &[&admin]), "InvalidConfig");
    let p = demo_params(&env, 1, 60, 2, 1);
    assert_err(try_create(&mut env, p), "DemoDisabled");
}
