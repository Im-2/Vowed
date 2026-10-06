use crate::common::*;
use solana_keypair::Keypair;
use solana_signer::Signer;
use vowed::state::PoolStatus;

fn setup(duration: u8, required: u8, tz: i16) -> (Env, PoolRef, User) {
    let mut env = Env::new();
    let pool = env.hard_pool(1, duration, required);
    let alice = env.new_user(100 * UNIT);
    env.join(&pool, &alice, 10 * UNIT, tz).unwrap();
    (env, pool, alice)
}

#[test]
fn checkin_records_the_day() {
    let (mut env, pool, alice) = setup(7, 5, 0);
    env.checkin_on_day(&pool, &alice.key(), 0).unwrap();
    env.checkin_on_day(&pool, &alice.key(), 1).unwrap();
    env.checkin_on_day(&pool, &alice.key(), 6).unwrap();
    let p = env.part_state(&pool, &alice.key());
    assert_eq!(p.days_completed, 3);
    assert_eq!(p.checkin_bitmap, 0b100_0011);
}

#[test]
fn checkin_only_by_the_oracle() {
    let (mut env, pool, alice) = setup(7, 5, 0);
    env.set_time(pool.day_start(0) + 3_600);
    // the user themself cannot self-certify
    let ix = env.ix_checkin(&alice.key(), &pool, &part_pda(&pool.key, &alice.key()), 0);
    assert_err(env.send(ix, &[&alice.kp]), "Unauthorized");
    // nor can the admin or a stranger
    let admin = env.admin.insecure_clone();
    let ix = env.ix_checkin(&pk_of(&admin), &pool, &part_pda(&pool.key, &alice.key()), 0);
    assert_err(env.send(ix, &[&admin]), "Unauthorized");
    let mallory = Keypair::new();
    env.svm.airdrop(&mallory.pubkey(), 1_000_000_000).unwrap();
    let ix = env.ix_checkin(&pk_of(&mallory), &pool, &part_pda(&pool.key, &alice.key()), 0);
    assert_err(env.send(ix, &[&mallory]), "Unauthorized");
    // oracle account named in the instruction but not signing (a stranger just pays the fee)
    let oracle = env.oracle.insecure_clone();
    let mut ix = env.ix_checkin(&pk_of(&oracle), &pool, &part_pda(&pool.key, &alice.key()), 0);
    for m in ix.accounts.iter_mut() {
        m.is_signer = false;
    }
    assert_fails(env.send(ix, &[&mallory]));
    assert_eq!(env.part_state(&pool, &alice.key()).days_completed, 0);
}

#[test]
fn duplicate_checkin_is_rejected() {
    let (mut env, pool, alice) = setup(7, 5, 0);
    env.checkin_on_day(&pool, &alice.key(), 0).unwrap();
    assert_err(env.checkin(&pool, &alice.key(), 0), "DuplicateCheckin");
    assert_eq!(env.part_state(&pool, &alice.key()).days_completed, 1);
}

#[test]
fn checkin_before_start_is_rejected() {
    let (mut env, pool, alice) = setup(7, 5, 0);
    env.set_time(pool.start_ts - 1);
    assert_err(env.checkin(&pool, &alice.key(), 0), "OutsideCheckinWindow");
}

#[test]
fn checkin_for_future_or_out_of_range_day_is_rejected() {
    let (mut env, pool, alice) = setup(7, 5, 0);
    env.set_time(pool.day_start(0) + 3_600);
    assert_err(env.checkin(&pool, &alice.key(), 1), "OutsideCheckinWindow");
    assert_err(env.checkin(&pool, &alice.key(), 7), "DayOutOfRange");
    assert_err(env.checkin(&pool, &alice.key(), 255), "DayOutOfRange");
    assert_eq!(env.part_state(&pool, &alice.key()).days_completed, 0);
}

#[test]
fn checkin_window_closes_after_grace() {
    let (mut env, pool, alice) = setup(7, 5, 0);
    // day 0 is still accepted just inside the 2h grace after local midnight...
    env.set_time(pool.day_start(1) + 7_200 - 1);
    env.checkin(&pool, &alice.key(), 0).unwrap();
    // ...and rejected one second later
    env.set_time(pool.day_start(1) + 7_200);
    assert_err(env.checkin(&pool, &alice.key(), 0), "OutsideCheckinWindow");
    // but day 1 is open
    env.checkin(&pool, &alice.key(), 1).unwrap();
}

#[test]
fn checkin_respects_the_users_timezone() {
    // UTC+2 user: their day 0 is the local day containing start_ts.
    let (mut env, pool, alice) = setup(7, 5, 120);
    // 21:59 UTC on day 0 is 23:59 local: still day 0, and day 1 is not open yet
    env.set_time(pool.day_start(0) + 21 * 3_600 + 59 * 60);
    assert_err(env.checkin(&pool, &alice.key(), 1), "OutsideCheckinWindow");
    // 22:00 UTC is 00:00 local on day 1: now day 1 opens
    env.set_time(pool.day_start(0) + 22 * 3_600);
    env.checkin(&pool, &alice.key(), 1).unwrap();
    // UTC-5 user
    let mut env2 = Env::new();
    let pool2 = env2.hard_pool(1, 7, 5);
    let bob = env2.new_user(100 * UNIT);
    env2.join(&pool2, &bob, 10 * UNIT, -300).unwrap();
    // pool starts at 00:00 UTC = 19:00 local the previous day, so local day 0 ends at 05:00 UTC
    env2.set_time(pool2.day_start(0) + 4 * 3_600 + 59 * 60);
    assert_err(env2.checkin(&pool2, &bob.key(), 1), "OutsideCheckinWindow");
    env2.set_time(pool2.day_start(0) + 5 * 3_600);
    env2.checkin(&pool2, &bob.key(), 1).unwrap();
}

#[test]
fn cannot_substitute_another_pools_or_users_participation() {
    let mut env = Env::new();
    let pool_a = env.hard_pool(1, 7, 5);
    let pool_b = env.hard_pool(2, 7, 5);
    let alice = env.new_user(100 * UNIT);
    let bob = env.new_user(100 * UNIT);
    env.join(&pool_a, &alice, 10 * UNIT, 0).unwrap();
    env.join(&pool_b, &bob, 10 * UNIT, 0).unwrap();
    env.set_time(pool_a.day_start(0) + 3_600);
    let oracle = env.oracle.insecure_clone();
    // pool A with bob's pool-B participation
    let ix = env.ix_checkin(&pk_of(&oracle), &pool_a, &part_pda(&pool_b.key, &bob.key()), 0);
    assert_fails(env.send(ix, &[&oracle]));
    // a participation account that does not exist
    let ix = env.ix_checkin(&pk_of(&oracle), &pool_a, &part_pda(&pool_a.key, &bob.key()), 0);
    assert_fails(env.send(ix, &[&oracle]));
    assert_eq!(env.part_state(&pool_a, &alice.key()).days_completed, 0);
    assert_eq!(env.part_state(&pool_b, &bob.key()).days_completed, 0);
}

#[test]
fn checkin_closed_once_settlement_time_is_reached() {
    let (mut env, pool, alice) = setup(2, 1, 0);
    env.set_time(pool.settle_after());
    assert_err(env.checkin(&pool, &alice.key(), 1), "OutsideCheckinWindow");
}

#[test]
fn checkin_rejected_after_settlement_and_when_voided() {
    let (mut env, pool, alice) = setup(2, 1, 0);
    env.settle_all(&pool, &[&alice]);
    assert_eq!(env.pool_state(&pool).status, PoolStatus::Settled);
    assert_err(env.checkin(&pool, &alice.key(), 1), "InvalidPoolStatus");

    let (mut env, pool, alice) = setup(2, 1, 0);
    env.void(&pool).unwrap();
    env.set_time(pool.day_start(0) + 3_600);
    assert_err(env.checkin(&pool, &alice.key(), 0), "InvalidPoolStatus");
}
