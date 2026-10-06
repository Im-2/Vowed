use crate::common::*;
use anchor_lang::prelude::Pubkey as Pk;
use solana_keypair::Keypair;
use solana_signer::Signer;
use vowed::state::StakeMode;

#[test]
fn init_config_sets_fields() {
    let env = Env::with_fee(250);
    let c = env.config_state();
    assert_eq!(c.admin, pk_of(&env.admin));
    assert_eq!(c.oracle, pk_of(&env.oracle));
    assert_eq!(c.treasury, pk_of(&env.treasury));
    assert_eq!(c.fee_bps, 250);
    assert!(!c.paused);
    assert_eq!(c.max_stake, MAX_STAKE);
    assert_eq!(c.allowed_mint_count, 2);
    assert!(c.is_mint_allowed(&env.mint));
    assert!(c.is_mint_allowed(&env.other_mint));
    assert!(!c.is_mint_allowed(&env.third_mint));
    assert!(c.demo_enabled);
    assert_eq!(c.demo_max_stake, DEMO_MAX_STAKE);
    assert!(c.is_demo_mint(&env.mint));
    assert!(!c.is_demo_mint(&env.other_mint)); // allowed for normal pools, not for demo pools
}

#[test]
fn init_config_only_by_upgrade_authority() {
    // Anyone else racing to initialise a fresh deployment is rejected.
    let mut env = Env::bare();
    let mallory = Keypair::new();
    env.svm.airdrop(&mallory.pubkey(), 1_000_000_000).unwrap();
    let ix = env.ix_init_config(&pk_of(&mallory), env.init_params(0));
    assert_err(env.send(ix, &[&mallory]), "NotUpgradeAuthority");
}

#[test]
fn init_config_cannot_run_twice() {
    let mut env = Env::new();
    let admin = env.admin.insecure_clone();
    let ix = env.ix_init_config(&pk_of(&admin), env.init_params(0));
    assert_fails(env.send(ix, &[&admin])); // account already in use: no reinitialisation
    assert_eq!(env.config_state().fee_bps, 0);
}

#[test]
fn init_config_rejects_bad_params() {
    let mut env = Env::bare();
    let admin = env.admin.insecure_clone();
    let good = env.init_params(0);

    let mut p = good.clone();
    p.fee_bps = 1_001;
    let ix = env.ix_init_config(&pk_of(&admin), p);
    assert_err(env.send(ix, &[&admin]), "InvalidConfig");

    let mut p = good.clone();
    p.allowed_mints = vec![];
    let ix = env.ix_init_config(&pk_of(&admin), p);
    assert_err(env.send(ix, &[&admin]), "InvalidConfig");

    let mut p = good.clone();
    p.allowed_mints = vec![env.mint, env.mint];
    let ix = env.ix_init_config(&pk_of(&admin), p);
    assert_err(env.send(ix, &[&admin]), "InvalidConfig");

    let mut p = good.clone();
    p.allowed_mints = vec![Pk::default()];
    let ix = env.ix_init_config(&pk_of(&admin), p);
    assert_err(env.send(ix, &[&admin]), "InvalidConfig");

    let mut p = good.clone();
    p.max_stake = 0;
    let ix = env.ix_init_config(&pk_of(&admin), p);
    assert_err(env.send(ix, &[&admin]), "InvalidConfig");

    let mut p = good.clone();
    p.settle_grace_secs = 3 * DAY;
    let ix = env.ix_init_config(&pk_of(&admin), p);
    assert_err(env.send(ix, &[&admin]), "InvalidConfig");

    let mut p = good;
    p.oracle = Pk::default();
    let ix = env.ix_init_config(&pk_of(&admin), p);
    assert_err(env.send(ix, &[&admin]), "InvalidConfig");
}

#[test]
fn only_admin_can_pause_and_rotate_oracle() {
    let mut env = Env::new();
    let mallory = Keypair::new();
    env.svm.airdrop(&mallory.pubkey(), 1_000_000_000).unwrap();

    let ix = env.ix_set_paused(&pk_of(&mallory), true);
    assert_err(env.send(ix, &[&mallory]), "Unauthorized");
    assert!(!env.config_state().paused);

    let ix = env.ix_update_oracle(&pk_of(&mallory), pk_of(&mallory));
    assert_err(env.send(ix, &[&mallory]), "Unauthorized");
    assert_eq!(env.config_state().oracle, pk_of(&env.oracle));

    // the oracle key itself has no admin rights either
    let oracle = env.oracle.insecure_clone();
    let ix = env.ix_set_paused(&pk_of(&oracle), true);
    assert_err(env.send(ix, &[&oracle]), "Unauthorized");

    env.set_paused(true);
    assert!(env.config_state().paused);
    env.set_paused(false);
    assert!(!env.config_state().paused);
}

#[test]
fn update_oracle_rotates_the_checkin_signer() {
    let mut env = Env::new();
    let pool = env.hard_pool(1, 3, 2);
    let alice = env.new_user(100 * UNIT);
    env.join(&pool, &alice, 10 * UNIT, 0).unwrap();

    let new_oracle = Keypair::new();
    env.svm.airdrop(&new_oracle.pubkey(), 1_000_000_000).unwrap();
    let admin = env.admin.insecure_clone();
    let ix = env.ix_update_oracle(&pk_of(&admin), pk_of(&new_oracle));
    env.send(ix, &[&admin]).unwrap();

    env.set_time(pool.day_start(0) + 3_600);
    // old oracle is now rejected
    assert_err(env.checkin(&pool, &alice.key(), 0), "Unauthorized");
    // new oracle works
    let ix = env.ix_checkin(&pk_of(&new_oracle), &pool, &part_pda(&pool.key, &alice.key()), 0);
    env.send(ix, &[&new_oracle]).unwrap();
    assert_eq!(env.part_state(&pool, &alice.key()).days_completed, 1);

    // zero address is not a valid oracle
    let ix = env.ix_update_oracle(&pk_of(&admin), Pk::default());
    assert_err(env.send(ix, &[&admin]), "InvalidConfig");
}

#[test]
fn pause_blocks_create_and_join_but_not_settlement() {
    let mut env = Env::new();
    let pool = env.hard_pool(1, 2, 1);
    let alice = env.new_user(100 * UNIT);
    let bob = env.new_user(100 * UNIT);
    env.join(&pool, &alice, 10 * UNIT, 0).unwrap();

    env.set_paused(true);
    // join blocked
    assert_err(env.join(&pool, &bob, 10 * UNIT, 0), "Paused");
    // create blocked
    let params = env.create_params(2, StakeMode::Hard, 10_000, 2, 1);
    let (r, _) = env.create_pool_with(params);
    assert_err(r, "Paused");

    // users can still get out: settle and claim keep working while paused
    env.checkin_on_day(&pool, &alice.key(), 0).unwrap();
    env.settle_all(&pool, &[&alice]);
    env.claim(&pool, &alice).unwrap();
    assert_eq!(env.bal(&alice.token), 100 * UNIT);
}
