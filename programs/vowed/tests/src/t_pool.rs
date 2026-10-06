use crate::common::*;
use vowed::state::{ParticipationStatus, PoolStatus, StakeMode};

#[test]
fn create_pool_sets_state_and_vault() {
    let mut env = Env::with_fee(100);
    let pool = env.hard_pool(1, 7, 5);
    let s = env.pool_state(&pool);
    assert_eq!(s.id, 1);
    assert_eq!(s.creator, pool.creator);
    assert_eq!(s.mint, env.mint);
    assert_eq!(s.vault, pool.vault);
    assert_eq!(s.penalty_bps, 10_000);
    assert_eq!(s.fee_bps, 100, "fee is snapshotted at creation");
    assert_eq!(s.start_ts, BASE + DAY);
    assert_eq!(s.end_ts, BASE + DAY + 7 * DAY);
    assert_eq!(s.join_deadline_ts, BASE + DAY + 3_600);
    assert_eq!(s.settle_after_ts, s.end_ts + SETTLE_GRACE);
    // normal pools keep real 24-hour days and are not demo pools
    assert!(!s.is_demo);
    assert_eq!(s.day_secs, 86_400);
    assert_eq!(s.goal_hash, [7u8; 32]);
    assert_eq!(s.status, PoolStatus::Open);
    // vault: owned by the token program, authority is the pool PDA, right mint, empty
    assert_eq!(env.token_owner(&pool.vault), pool.key);
    assert_eq!(env.bal(&pool.vault), 0);
    let acc = env.svm.get_account(&addr(&pool.vault)).unwrap();
    assert_eq!(&acc.data[0..32], env.mint.as_ref());
}

#[test]
fn create_pool_validates_params() {
    let mut env = Env::new();
    let base = env.create_params(1, StakeMode::Hard, 10_000, 7, 5);
    let try_params = |env: &mut Env, p: vowed::instructions::CreatePoolParams| env.create_pool_with(p).0;

    let mut p = base.clone();
    p.duration_days = 0;
    assert_err(try_params(&mut env, p), "InvalidPoolParams");
    let mut p = base.clone();
    p.duration_days = 61;
    p.required_days = 5;
    assert_err(try_params(&mut env, p), "InvalidPoolParams");
    let mut p = base.clone();
    p.required_days = 0;
    assert_err(try_params(&mut env, p), "InvalidPoolParams");
    let mut p = base.clone();
    p.required_days = 8;
    assert_err(try_params(&mut env, p), "InvalidPoolParams");
    let mut p = base.clone();
    p.start_ts = BASE; // not in the future
    assert_err(try_params(&mut env, p), "InvalidPoolParams");
    let mut p = base.clone();
    p.start_ts = BASE + 31 * DAY;
    assert_err(try_params(&mut env, p), "InvalidPoolParams");
    let mut p = base.clone();
    p.join_window_secs = DAY + 1;
    assert_err(try_params(&mut env, p), "InvalidPoolParams");
    let mut p = base.clone();
    p.join_window_secs = -1;
    assert_err(try_params(&mut env, p), "InvalidPoolParams");
    let mut p = base.clone();
    p.max_participants = 0;
    assert_err(try_params(&mut env, p), "InvalidPoolParams");
    let mut p = base.clone();
    p.max_participants = 1_001;
    assert_err(try_params(&mut env, p), "InvalidPoolParams");
    let mut p = base.clone();
    p.demo_day_secs = 0;
    p.start_ts = BASE + 31 * DAY; // normal pools may start up to 30 days ahead, no more
    assert_err(try_params(&mut env, p), "InvalidPoolParams");
    // Hard must forfeit everything
    let mut p = base.clone();
    p.penalty_bps = 9_999;
    assert_err(try_params(&mut env, p), "InvalidPoolParams");
    // Soft is capped at 50%, and must be non-zero
    let mut p = base.clone();
    p.mode = StakeMode::Soft;
    p.penalty_bps = 5_001;
    assert_err(try_params(&mut env, p), "InvalidPoolParams");
    let mut p = base.clone();
    p.mode = StakeMode::Soft;
    p.penalty_bps = 0;
    assert_err(try_params(&mut env, p), "InvalidPoolParams");
    // limits are inclusive
    let mut p = base.clone();
    p.duration_days = 60;
    p.required_days = 60;
    p.pool_id = 100;
    try_params(&mut env, p).unwrap();
    let mut p = base;
    p.mode = StakeMode::Soft;
    p.penalty_bps = 5_000;
    p.pool_id = 101;
    try_params(&mut env, p).unwrap();
}

#[test]
fn create_pool_rejects_unlisted_mint_and_wrong_token_program() {
    let mut env = Env::new();
    let funder = env.funder.insecure_clone();
    let params = env.create_params(1, StakeMode::Hard, 10_000, 7, 5);
    // a mint that is not on the allowed list
    let ix = env.ix_create_pool(&pk_of(&funder), &env.third_mint.clone(), params.clone());
    assert_err(env.send(ix, &[&funder]), "MintNotAllowed");
    // the allowed mint paired with the Token-2022 program: the mint is not owned by that program
    let mut ix = env.ix_create_pool(&pk_of(&funder), &env.mint.clone(), params);
    let t22 = addr(&anchor_spl::token_2022::ID);
    for m in ix.accounts.iter_mut() {
        if m.pubkey == addr(&anchor_spl::token::ID) {
            m.pubkey = t22;
        }
    }
    assert_fails(env.send(ix, &[&funder]));
}

#[test]
fn create_pool_cannot_be_repeated_or_squatted() {
    let mut env = Env::new();
    env.hard_pool(1, 7, 5);
    let params = env.create_params(1, StakeMode::Hard, 10_000, 3, 1);
    let (r, _) = env.create_pool_with(params);
    assert_fails(r); // already in use
    // another creator with the same id gets a different pool (creator is in the seeds)
    let other = pk_of(&solana_keypair::Keypair::new());
    assert_ne!(pool_pda(&other, 1), pool_pda(&pk_of(&env.funder), 1));
}

#[test]
fn join_moves_stake_into_vault() {
    let mut env = Env::new();
    let pool = env.hard_pool(1, 7, 5);
    let alice = env.new_user(100 * UNIT);
    let bob = env.new_user(50 * UNIT);
    env.join(&pool, &alice, 30 * UNIT, 60).unwrap();
    env.join(&pool, &bob, 50 * UNIT, -300).unwrap();
    assert_eq!(env.bal(&pool.vault), 80 * UNIT);
    assert_eq!(env.bal(&alice.token), 70 * UNIT);
    assert_eq!(env.bal(&bob.token), 0);
    let s = env.pool_state(&pool);
    assert_eq!(s.participant_count, 2);
    assert_eq!(s.total_deposits, 80 * UNIT);
    let p = env.part_state(&pool, &alice.key());
    assert_eq!(p.user, alice.key());
    assert_eq!(p.pool, pool.key);
    assert_eq!(p.stake, 30 * UNIT);
    assert_eq!(p.tz_offset_minutes, 60);
    assert_eq!(p.status, ParticipationStatus::Active);
    assert_eq!(p.days_completed, 0);
    assert_eq!(p.device_key_hash, [9u8; 32]);
}

#[test]
fn join_validates_amounts_and_timezone() {
    let mut env = Env::new();
    let pool = env.hard_pool(1, 7, 5);
    let alice = env.new_user(MAX_STAKE + UNIT);
    assert_err(env.join(&pool, &alice, 0, 0), "StakeZero");
    assert_err(env.join(&pool, &alice, MAX_STAKE + 1, 0), "StakeTooLarge");
    assert_err(env.join(&pool, &alice, u64::MAX, 0), "StakeTooLarge");
    assert_err(env.join(&pool, &alice, UNIT, -721), "InvalidTimezone");
    assert_err(env.join(&pool, &alice, UNIT, 841), "InvalidTimezone");
    // boundaries are accepted
    env.join(&pool, &alice, MAX_STAKE, 840).unwrap();
    let bob = env.new_user(UNIT);
    env.join(&pool, &bob, UNIT, -720).unwrap();
}

#[test]
fn join_twice_is_rejected() {
    let mut env = Env::new();
    let pool = env.hard_pool(1, 7, 5);
    let alice = env.new_user(100 * UNIT);
    env.join(&pool, &alice, 10 * UNIT, 0).unwrap();
    assert_fails(env.join(&pool, &alice, 10 * UNIT, 0));
    assert_eq!(env.bal(&pool.vault), 10 * UNIT);
}

#[test]
fn join_window_is_inclusive_of_the_last_second() {
    let mut env = Env::new();
    let pool = env.hard_pool(1, 7, 5);
    let late = env.new_user(10 * UNIT);
    let last = env.new_user(10 * UNIT);
    let early = env.new_user(10 * UNIT);
    // joining before the start is fine
    env.set_time(BASE + 10);
    env.join(&pool, &early, UNIT, 0).unwrap();
    // last allowed second: start + 3600
    env.set_time(pool.start_ts + 3_600);
    env.join(&pool, &last, UNIT, 0).unwrap();
    // one second later is too late
    env.set_time(pool.start_ts + 3_601);
    assert_err(env.join(&pool, &late, UNIT, 0), "JoinClosed");
}

#[test]
fn pool_capacity_is_enforced() {
    let mut env = Env::new();
    let mut params = env.create_params(1, StakeMode::Hard, 10_000, 7, 5);
    params.max_participants = 2;
    let (r, pool) = env.create_pool_with(params);
    r.unwrap();
    for _ in 0..2 {
        let u = env.new_user(UNIT);
        env.join(&pool, &u, UNIT, 0).unwrap();
    }
    let third = env.new_user(UNIT);
    assert_err(env.join(&pool, &third, UNIT, 0), "PoolFull");
}

#[test]
fn join_rejects_bad_token_accounts() {
    let mut env = Env::new();
    let pool = env.hard_pool(1, 7, 5);
    let alice = env.new_user(100 * UNIT);
    let bob = env.new_user(100 * UNIT);

    // someone else's token account (wrong authority)
    let ix = env.ix_join(&pool, &alice.key(), &bob.token, UNIT, 0);
    assert_fails(env.send(ix, &[&alice.kp]));
    assert_eq!(env.bal(&bob.token), 100 * UNIT);

    // token account of the wrong mint
    let other_token = env.token_account(&alice.kp.insecure_clone(), &env.other_mint.clone(), 100 * UNIT);
    let ix = env.ix_join(&pool, &alice.key(), &other_token, UNIT, 0);
    assert_fails(env.send(ix, &[&alice.kp]));

    // a different vault than the pool's
    let mut ix = env.ix_join(&pool, &alice.key(), &alice.token, UNIT, 0);
    let evil = alice.token;
    for m in ix.accounts.iter_mut() {
        if m.pubkey == addr(&pool.vault) {
            m.pubkey = addr(&evil);
        }
    }
    assert_fails(env.send(ix, &[&alice.kp]));
    assert_eq!(env.bal(&alice.token), 100 * UNIT);
    assert_eq!(env.pool_state(&pool).participant_count, 0);
}

#[test]
fn join_requires_the_user_to_sign() {
    let mut env = Env::new();
    let pool = env.hard_pool(1, 7, 5);
    let alice = env.new_user(100 * UNIT);
    let funder = env.funder.insecure_clone();
    // funder signs and pays, but alice (the user and token authority) does not
    let mut ix = env.ix_join(&pool, &alice.key(), &alice.token, UNIT, 0);
    for m in ix.accounts.iter_mut() {
        if m.pubkey == addr(&alice.key()) {
            m.is_signer = false;
        }
    }
    assert_fails(env.send(ix, &[&funder]));
    assert_eq!(env.bal(&alice.token), 100 * UNIT);
}
