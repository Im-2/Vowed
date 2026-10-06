use crate::common::*;
use solana_keypair::Keypair;
use solana_signer::Signer;
use vowed::state::{ParticipationStatus, PoolStatus, StakeMode};

/// Join users with (stake, days to complete). Returns the users.
fn join_all(env: &mut Env, pool: &PoolRef, specs: &[(u64, u8)]) -> Vec<User> {
    let mut users = vec![];
    for (stake, _) in specs {
        let u = env.new_user(*stake);
        env.join(pool, &u, *stake, 0).unwrap();
        users.push(u);
    }
    for (u, (_, days)) in users.iter().zip(specs) {
        for d in 0..*days {
            env.checkin_on_day(pool, &u.key(), d).unwrap();
        }
    }
    users
}

#[test]
fn settle_is_not_allowed_before_the_grace_period_ends() {
    let mut env = Env::new();
    let pool = env.hard_pool(1, 3, 2);
    let users = join_all(&mut env, &pool, &[(10 * UNIT, 3)]);
    env.set_time(pool.end_ts());
    assert_err(env.settle(&pool, &users[0].key()), "NotEnded");
    env.set_time(pool.settle_after() - 1);
    assert_err(env.settle(&pool, &users[0].key()), "NotEnded");
    env.set_time(pool.settle_after());
    env.settle(&pool, &users[0].key()).unwrap();
}

#[test]
fn settle_twice_is_rejected() {
    let mut env = Env::new();
    let pool = env.hard_pool(1, 3, 2);
    let users = join_all(&mut env, &pool, &[(10 * UNIT, 3), (10 * UNIT, 0)]);
    env.set_time(pool.settle_after());
    env.settle(&pool, &users[0].key()).unwrap();
    assert_err(env.settle(&pool, &users[0].key()), "AlreadySettled");
    assert_eq!(env.pool_state(&pool).settled_count, 1);
}

#[test]
fn pool_status_moves_open_settling_settled() {
    let mut env = Env::new();
    let pool = env.hard_pool(1, 3, 2);
    let users = join_all(&mut env, &pool, &[(10 * UNIT, 3), (10 * UNIT, 0)]);
    assert_eq!(env.pool_state(&pool).status, PoolStatus::Open);
    env.set_time(pool.settle_after());
    env.settle(&pool, &users[0].key()).unwrap();
    assert_eq!(env.pool_state(&pool).status, PoolStatus::Settling);
    assert_err(env.claim(&pool, &users[0]), "NotSettled"); // claim before Settled
    env.settle(&pool, &users[1].key()).unwrap();
    assert_eq!(env.pool_state(&pool).status, PoolStatus::Settled);
}

#[test]
fn claim_before_settlement_and_by_wrong_signer_fails() {
    let mut env = Env::new();
    let pool = env.hard_pool(1, 3, 2);
    let users = join_all(&mut env, &pool, &[(10 * UNIT, 3), (10 * UNIT, 0)]);
    assert_err(env.claim(&pool, &users[0]), "NotSettled"); // pool still Open
    env.settle_all(&pool, &[&users[0], &users[1]]);

    // bob tries to claim alice's winnings into his own account: seeds use the signer, so his own
    // (failed) participation is used instead
    let bob = &users[1];
    let alice = &users[0];
    let ix = env.ix_claim(&bob.key(), &pool, &part_pda(&pool.key, &alice.key()), &bob.token);
    assert_fails(env.send(ix, &[&bob.kp]));
    // alice's participation with alice's token account but signed by someone else
    let mallory = Keypair::new();
    env.svm.airdrop(&mallory.pubkey(), 1_000_000_000).unwrap();
    let ix = env.ix_claim(&pk_of(&mallory), &pool, &part_pda(&pool.key, &alice.key()), &alice.token);
    assert_fails(env.send(ix, &[&mallory]));
    // paying out to an account the owner does not control
    let ix = env.ix_claim(&alice.key(), &pool, &part_pda(&pool.key, &alice.key()), &bob.token);
    assert_fails(env.send(ix, &[&alice.kp]));
    assert_eq!(env.part_state(&pool, &alice.key()).status, ParticipationStatus::Succeeded);
}

#[test]
fn claim_twice_is_rejected() {
    let mut env = Env::new();
    let pool = env.hard_pool(1, 3, 2);
    let users = join_all(&mut env, &pool, &[(10 * UNIT, 3), (10 * UNIT, 0)]);
    env.settle_all(&pool, &[&users[0], &users[1]]);
    env.claim(&pool, &users[0]).unwrap();
    assert_err(env.claim(&pool, &users[0]), "AlreadyClaimed");
    assert_eq!(env.bal(&users[0].token), 20 * UNIT); // stake + loser's 10
}

#[test]
fn claim_rejects_wrong_vault_mint_or_token_owner() {
    let mut env = Env::new();
    let pool = env.hard_pool(1, 3, 2);
    let users = join_all(&mut env, &pool, &[(10 * UNIT, 3), (10 * UNIT, 0)]);
    env.settle_all(&pool, &[&users[0], &users[1]]);
    let alice = &users[0];
    // a token account of the wrong mint
    let other = env.token_account(&alice.kp.insecure_clone(), &env.other_mint.clone(), 0);
    let ix = env.ix_claim(&alice.key(), &pool, &part_pda(&pool.key, &alice.key()), &other);
    assert_fails(env.send(ix, &[&alice.kp]));
    // a different vault: the treasury token account
    let mut ix = env.ix_claim(&alice.key(), &pool, &part_pda(&pool.key, &alice.key()), &alice.token);
    for m in ix.accounts.iter_mut() {
        if m.pubkey == addr(&pool.vault) {
            m.pubkey = addr(&env.treasury_token);
        }
    }
    assert_fails(env.send(ix, &[&alice.kp]));
    env.claim(&pool, alice).unwrap();
}

#[test]
fn standard_payout_two_winners_one_loser() {
    // stakes 100, 300 succeed; 200 fails (hard). F = 200, no fee. D = 200.
    // A: 100 + 200*100/400 = 150 ; B: 300 + 200*300/400 = 450 ; C: nothing.
    let mut env = Env::new();
    let pool = env.hard_pool(1, 3, 2);
    let users = join_all(&mut env, &pool, &[(100 * UNIT, 2), (300 * UNIT, 3), (200 * UNIT, 1)]);
    env.settle_all(&pool, &[&users[0], &users[1], &users[2]]);
    let s = env.pool_state(&pool);
    assert_eq!(s.total_forfeit, 200 * UNIT);
    assert_eq!(s.total_success_stake, 400 * UNIT);
    assert_eq!(s.distributable, 200 * UNIT);
    env.claim(&pool, &users[0]).unwrap();
    env.claim(&pool, &users[1]).unwrap();
    assert_eq!(env.bal(&users[0].token), 150 * UNIT);
    assert_eq!(env.bal(&users[1].token), 450 * UNIT);
    // the hard-mode loser has nothing to claim and must not block the sweep
    assert_err(env.claim(&pool, &users[2]), "NothingToClaim");
    assert_eq!(env.bal(&pool.vault), 0);
    assert_err(env.sweep(&pool), "NothingToSweep");
    assert_eq!(env.total_in_users_and_treasury(&[&users[0], &users[1], &users[2]]), 600 * UNIT);
}

#[test]
fn rounding_dust_goes_to_the_treasury() {
    // winners stake 1 and 2 base units, loser forfeits 10: shares floor(10/3)=3 and floor(20/3)=6, dust 1.
    let mut env = Env::new();
    let pool = env.hard_pool(1, 2, 1);
    let users = join_all(&mut env, &pool, &[(1, 1), (2, 1), (10, 0)]);
    env.settle_all(&pool, &[&users[0], &users[1], &users[2]]);
    env.claim(&pool, &users[0]).unwrap();
    env.claim(&pool, &users[1]).unwrap();
    assert_eq!(env.bal(&users[0].token), 1 + 3);
    assert_eq!(env.bal(&users[1].token), 2 + 6);
    assert_eq!(env.bal(&pool.vault), 1);
    env.sweep(&pool).unwrap();
    assert_eq!(env.bal(&env.treasury_token.clone()), 1);
    assert_eq!(env.bal(&pool.vault), 0);
}

#[test]
fn fee_is_taken_from_forfeits_and_swept() {
    // 5% fee. Loser forfeits 1000 -> fee 50, D = 950 to the single winner.
    let mut env = Env::with_fee(500);
    let pool = env.hard_pool(1, 2, 1);
    let users = join_all(&mut env, &pool, &[(1_000, 1), (1_000, 0)]);
    env.settle_all(&pool, &[&users[0], &users[1]]);
    let s = env.pool_state(&pool);
    assert_eq!((s.fee_amount, s.distributable), (50, 950));
    env.claim(&pool, &users[0]).unwrap();
    assert_eq!(env.bal(&users[0].token), 1_000 + 950);
    env.sweep(&pool).unwrap();
    assert_eq!(env.bal(&env.treasury_token.clone()), 50);
    assert_eq!(env.total_in_users_and_treasury(&[&users[0], &users[1]]), 2_000);
}

#[test]
fn soft_mode_returns_part_of_the_stake() {
    // soft 30%: loser (1000) gets 700 back, 300 forfeited to the winner (stake 500).
    let mut env = Env::new();
    let pool = env.pool(1, StakeMode::Soft, 3_000, 2, 1);
    let users = join_all(&mut env, &pool, &[(500, 1), (1_000, 0)]);
    env.settle_all(&pool, &[&users[0], &users[1]]);
    env.claim(&pool, &users[1]).unwrap();
    assert_eq!(env.bal(&users[1].token), 700);
    // the loser still has something to claim, so the sweep waits for the winner too
    assert_err(env.sweep(&pool), "ClaimsPending");
    env.claim(&pool, &users[0]).unwrap();
    assert_eq!(env.bal(&users[0].token), 500 + 300);
    assert_err(env.sweep(&pool), "NothingToSweep");
    assert_eq!(env.total_in_users_and_treasury(&[&users[0], &users[1]]), 1_500);
}

#[test]
fn nobody_succeeds_everything_forfeited_goes_to_treasury() {
    let mut env = Env::new();
    let pool = env.hard_pool(1, 3, 3);
    let users = join_all(&mut env, &pool, &[(100, 2), (200, 0)]);
    env.settle_all(&pool, &[&users[0], &users[1]]);
    assert_eq!(env.pool_state(&pool).total_success_stake, 0);
    assert_err(env.claim(&pool, &users[0]), "NothingToClaim");
    env.sweep(&pool).unwrap();
    assert_eq!(env.bal(&env.treasury_token.clone()), 300);
    assert_eq!(env.bal(&pool.vault), 0);
}

#[test]
fn single_participant_succeeds_gets_stake_back_single_fails_loses_it() {
    let mut env = Env::new();
    let pool = env.hard_pool(1, 2, 2);
    let users = join_all(&mut env, &pool, &[(500, 2)]);
    env.settle_all(&pool, &[&users[0]]);
    env.claim(&pool, &users[0]).unwrap();
    assert_eq!(env.bal(&users[0].token), 500);
    assert_eq!(env.bal(&pool.vault), 0);

    let mut env = Env::new();
    let pool = env.hard_pool(1, 2, 2);
    let users = join_all(&mut env, &pool, &[(500, 1)]);
    env.settle_all(&pool, &[&users[0]]);
    assert_err(env.claim(&pool, &users[0]), "NothingToClaim");
    env.sweep(&pool).unwrap();
    assert_eq!(env.bal(&env.treasury_token.clone()), 500);
}

#[test]
fn everyone_succeeds_everyone_just_gets_their_stake() {
    let mut env = Env::new();
    let pool = env.hard_pool(1, 2, 2);
    let users = join_all(&mut env, &pool, &[(100, 2), (250, 2), (7, 2)]);
    env.settle_all(&pool, &[&users[0], &users[1], &users[2]]);
    for (u, stake) in users.iter().zip([100u64, 250, 7]) {
        env.claim(&pool, u).unwrap();
        assert_eq!(env.bal(&u.token), stake);
    }
    assert_eq!(env.bal(&pool.vault), 0);
    assert_err(env.sweep(&pool), "NothingToSweep");
}

#[test]
fn joining_at_the_last_moment_still_settles_correctly() {
    let mut env = Env::new();
    let pool = env.hard_pool(1, 3, 1);
    let early = env.new_user(100);
    env.join(&pool, &early, 100, 0).unwrap();
    env.checkin_on_day(&pool, &early.key(), 0).unwrap();
    let late = env.new_user(100);
    env.set_time(pool.start_ts + 3_600); // last allowed second
    env.join(&pool, &late, 100, 0).unwrap();
    env.settle_all(&pool, &[&early, &late]);
    env.claim(&pool, &early).unwrap();
    assert_eq!(env.bal(&early.token), 200);
}

#[test]
fn sweep_is_blocked_until_claims_are_done_and_pool_is_settled() {
    let mut env = Env::new();
    let pool = env.hard_pool(1, 2, 1);
    let users = join_all(&mut env, &pool, &[(100, 1), (100, 0)]);
    assert_err(env.sweep(&pool), "NotSettled"); // Open
    env.settle_all(&pool, &[&users[0], &users[1]]);
    assert_err(env.sweep(&pool), "ClaimsPending"); // the winner has not claimed yet
    assert_eq!(env.bal(&env.treasury_token.clone()), 0);
}

#[test]
fn sweep_must_go_to_the_treasurys_token_account() {
    let mut env = Env::new();
    let pool = env.hard_pool(1, 2, 2);
    let users = join_all(&mut env, &pool, &[(100, 0)]);
    env.settle_all(&pool, &[&users[0]]);
    // a token account owned by someone else is rejected
    let attacker = Keypair::new();
    env.svm.airdrop(&attacker.pubkey(), 1_000_000_000).unwrap();
    let theirs = env.token_account(&attacker, &env.mint.clone(), 0);
    let ix = env.ix_sweep(&pk_of(&attacker), &pool, &theirs);
    assert_fails(env.send(ix, &[&attacker]));
    assert_eq!(env.bal(&pool.vault), 100);
    // and the real one works for anyone
    env.sweep(&pool).unwrap();
    assert_eq!(env.bal(&env.treasury_token.clone()), 100);
}

#[test]
fn settlement_can_be_cranked_by_anyone_in_any_order() {
    let mut env = Env::new();
    let pool = env.hard_pool(1, 2, 1);
    let users = join_all(&mut env, &pool, &[(100, 1), (100, 1), (100, 0)]);
    env.set_time(pool.settle_after() + 5 * DAY);
    for i in [2, 0, 1] {
        env.settle(&pool, &users[i].key()).unwrap();
    }
    assert_eq!(env.pool_state(&pool).status, PoolStatus::Settled);
    // winners split the loser's 100 evenly
    env.claim(&pool, &users[0]).unwrap();
    env.claim(&pool, &users[1]).unwrap();
    assert_eq!(env.bal(&users[0].token), 150);
    assert_eq!(env.bal(&users[1].token), 150);
}
