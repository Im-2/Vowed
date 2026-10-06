use crate::common::*;
use solana_keypair::Keypair;
use solana_signer::Signer;
use vowed::state::PoolStatus;

fn three_users(env: &mut Env, pool: &PoolRef, stakes: [u64; 3]) -> Vec<User> {
    let mut v = vec![];
    for s in stakes {
        let u = env.new_user(s);
        env.join(pool, &u, s, 0).unwrap();
        v.push(u);
    }
    v
}

#[test]
fn void_refunds_everyone_in_full() {
    let mut env = Env::new();
    let pool = env.hard_pool(1, 3, 2);
    let users = three_users(&mut env, &pool, [100, 200, 300]);
    env.checkin_on_day(&pool, &users[0].key(), 0).unwrap();
    env.void(&pool).unwrap();
    assert_eq!(env.pool_state(&pool).status, PoolStatus::Voided);
    for (u, s) in users.iter().zip([100u64, 200, 300]) {
        env.claim(&pool, u).unwrap();
        assert_eq!(env.bal(&u.token), s);
    }
    assert_eq!(env.bal(&pool.vault), 0);
    assert_err(env.sweep(&pool), "NothingToSweep");
}

#[test]
fn only_admin_can_void() {
    let mut env = Env::new();
    let pool = env.hard_pool(1, 3, 2);
    three_users(&mut env, &pool, [100, 200, 300]);
    let mallory = Keypair::new();
    env.svm.airdrop(&mallory.pubkey(), 1_000_000_000).unwrap();
    let ix = env.ix_void(&pk_of(&mallory), &pool);
    assert_err(env.send(ix, &[&mallory]), "Unauthorized");
    // not the oracle either
    let oracle = env.oracle.insecure_clone();
    let ix = env.ix_void(&pk_of(&oracle), &pool);
    assert_err(env.send(ix, &[&oracle]), "Unauthorized");
    assert_eq!(env.pool_state(&pool).status, PoolStatus::Open);
}

#[test]
fn void_twice_or_after_settlement_is_rejected() {
    let mut env = Env::new();
    let pool = env.hard_pool(1, 2, 1);
    let users = three_users(&mut env, &pool, [100, 200, 300]);
    env.void(&pool).unwrap();
    assert_err(env.void(&pool), "InvalidPoolStatus");

    let mut env = Env::new();
    let pool = env.hard_pool(1, 2, 1);
    let users2 = three_users(&mut env, &pool, [100, 200, 300]);
    env.settle_all(&pool, &[&users2[0], &users2[1], &users2[2]]);
    assert_err(env.void(&pool), "InvalidPoolStatus");
    let _ = users;
}

#[test]
fn void_mid_settlement_still_refunds_full_stakes() {
    let mut env = Env::new();
    let pool = env.hard_pool(1, 2, 1);
    let users = three_users(&mut env, &pool, [100, 200, 300]);
    env.checkin_on_day(&pool, &users[0].key(), 0).unwrap();
    env.set_time(pool.settle_after());
    env.settle(&pool, &users[0].key()).unwrap(); // succeeded
    env.settle(&pool, &users[1].key()).unwrap(); // failed (hard: would have lost everything)
    assert_eq!(env.pool_state(&pool).status, PoolStatus::Settling);
    env.void(&pool).unwrap();
    // even the participant already marked Failed gets their whole stake back
    for (u, s) in users.iter().zip([100u64, 200, 300]) {
        env.claim(&pool, u).unwrap();
        assert_eq!(env.bal(&u.token), s);
    }
    assert_eq!(env.bal(&pool.vault), 0);
}

#[test]
fn void_blocks_join_settle_and_double_claim() {
    let mut env = Env::new();
    let pool = env.hard_pool(1, 2, 1);
    let users = three_users(&mut env, &pool, [100, 200, 300]);
    env.void(&pool).unwrap();
    let late = env.new_user(50);
    assert_err(env.join(&pool, &late, 50, 0), "InvalidPoolStatus");
    env.set_time(pool.settle_after());
    assert_err(env.settle(&pool, &users[0].key()), "InvalidPoolStatus");
    env.claim(&pool, &users[0]).unwrap();
    assert_err(env.claim(&pool, &users[0]), "AlreadyClaimed");
    assert_eq!(env.bal(&users[0].token), 100);
}
