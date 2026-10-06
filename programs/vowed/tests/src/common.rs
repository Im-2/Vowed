//! Shared LiteSVM harness for the Vowed program tests.
#![allow(dead_code)]

use anchor_lang::{
    prelude::Pubkey as Pk, AccountDeserialize, InstructionData, ToAccountMetas,
};
use litesvm::{types::TransactionResult, LiteSVM};
use litesvm_token::{CreateAssociatedTokenAccount, CreateMint, MintTo};
use solana_address::Address;
use solana_clock::Clock;
use solana_instruction::{AccountMeta, Instruction};
use solana_keypair::Keypair;
use solana_signer::Signer;
use solana_transaction::Transaction;
use vowed::{
    instructions::{CreatePoolParams, InitConfigParams},
    state::{Config, Participation, ParticipationStatus, Pool, PoolKind, PoolStatus, StakeMode},
};

pub const DAY: i64 = 86_400;
/// A UTC-midnight-aligned "now" (1_799_971_200 = 20_833 * 86_400).
pub const BASE: i64 = 1_799_971_200;
pub const DECIMALS: u8 = 6;
pub const UNIT: u64 = 1_000_000;
pub const MAX_STAKE: u64 = 1_000_000 * UNIT;
pub const SETTLE_GRACE: i64 = 2 * DAY;

pub fn addr(p: &Pk) -> Address {
    Address::new_from_array(p.to_bytes())
}
pub fn pk(a: &Address) -> Pk {
    Pk::new_from_array(a.to_bytes())
}
pub fn pk_of(k: &Keypair) -> Pk {
    Pk::new_from_array(k.pubkey().to_bytes())
}

pub fn program_id() -> Pk {
    vowed::ID
}
pub fn config_pda() -> Pk {
    Pk::find_program_address(&[b"config"], &program_id()).0
}
pub fn pool_pda(creator: &Pk, id: u64) -> Pk {
    Pk::find_program_address(&[b"pool", creator.as_ref(), &id.to_le_bytes()], &program_id()).0
}
pub fn vault_pda(pool: &Pk) -> Pk {
    Pk::find_program_address(&[b"vault", pool.as_ref()], &program_id()).0
}
pub fn part_pda(pool: &Pk, user: &Pk) -> Pk {
    Pk::find_program_address(&[b"part", pool.as_ref(), user.as_ref()], &program_id()).0
}
pub fn program_data_pda() -> Pk {
    Pk::find_program_address(
        &[program_id().as_ref()],
        &anchor_lang::solana_program::bpf_loader_upgradeable::ID,
    )
    .0
}

pub struct User {
    pub kp: Keypair,
    pub token: Pk,
}
impl User {
    pub fn key(&self) -> Pk {
        pk_of(&self.kp)
    }
}

#[derive(Clone)]
pub struct PoolRef {
    pub key: Pk,
    pub creator: Pk,
    pub id: u64,
    pub vault: Pk,
    pub mint: Pk,
    pub start_ts: i64,
    pub duration: u8,
}
impl PoolRef {
    pub fn day_start(&self, day: i64) -> i64 {
        self.start_ts + day * DAY
    }
    pub fn end_ts(&self) -> i64 {
        self.start_ts + self.duration as i64 * DAY
    }
    pub fn settle_after(&self) -> i64 {
        self.end_ts() + SETTLE_GRACE
    }
}

pub struct Env {
    pub svm: LiteSVM,
    pub admin: Keypair,
    pub oracle: Keypair,
    pub treasury: Keypair,
    pub treasury_token: Pk,
    pub funder: Keypair,
    pub mint: Pk,
    pub mint_auth: Keypair,
    pub other_mint: Pk,
}

fn so_path() -> String {
    format!("{}/../target/deploy/vowed.so", env!("CARGO_MANIFEST_DIR"))
}

impl Env {
    /// Program deployed, clock set to BASE, config initialised (fee 0).
    pub fn new() -> Self {
        Self::with_fee(0)
    }

    pub fn with_fee(fee_bps: u16) -> Self {
        let mut env = Self::bare();
        env.init_config_ok(fee_bps);
        env
    }

    /// Program deployed with `admin` as the upgrade authority, but config NOT initialised.
    pub fn bare() -> Self {
        let mut svm = LiteSVM::new();
        svm.add_program_from_file(addr(&program_id()), so_path())
            .expect("build the program first: anchor build");
        let admin = Keypair::new();
        let oracle = Keypair::new();
        let treasury = Keypair::new();
        let funder = Keypair::new();
        let mint_auth = Keypair::new();
        for k in [&admin, &oracle, &treasury, &funder, &mint_auth] {
            svm.airdrop(&k.pubkey(), 100_000_000_000).unwrap();
        }
        // LiteSVM deploys with no upgrade authority; make `admin` the authority so init_config can be tested.
        let pd = addr(&program_data_pda());
        let mut acc = svm.get_account(&pd).expect("programdata");
        acc.data[12] = 1; // Option::Some
        acc.data[13..45].copy_from_slice(&admin.pubkey().to_bytes());
        svm.set_account(pd, acc).unwrap();

        let mint = CreateMint::new(&mut svm, &funder)
            .authority(&mint_auth.pubkey())
            .decimals(DECIMALS)
            .send()
            .unwrap();
        let other_mint = CreateMint::new(&mut svm, &funder)
            .authority(&mint_auth.pubkey())
            .decimals(DECIMALS)
            .send()
            .unwrap();
        let treasury_token = CreateAssociatedTokenAccount::new(&mut svm, &funder, &mint)
            .owner(&treasury.pubkey())
            .send()
            .unwrap();

        let mut env = Env {
            svm,
            admin,
            oracle,
            treasury,
            treasury_token: pk(&treasury_token),
            funder,
            mint: pk(&mint),
            mint_auth,
            other_mint: pk(&other_mint),
        };
        env.set_time(BASE);
        env
    }

    pub fn init_params(&self, fee_bps: u16) -> InitConfigParams {
        InitConfigParams {
            oracle: pk_of(&self.oracle),
            treasury: pk_of(&self.treasury),
            fee_bps,
            max_stake: MAX_STAKE,
            settle_grace_secs: SETTLE_GRACE,
            allowed_mints: vec![self.mint],
        }
    }

    pub fn ix_init_config(&self, signer: &Pk, params: InitConfigParams) -> Instruction {
        Self::mk(
            vowed::accounts::InitConfig {
                admin: *signer,
                config: config_pda(),
                program: program_id(),
                program_data: program_data_pda(),
                system_program: anchor_lang::system_program::ID,
            },
            vowed::instruction::InitConfig { params },
        )
    }

    pub fn init_config_ok(&mut self, fee_bps: u16) {
        let ix = self.ix_init_config(&pk_of(&self.admin), self.init_params(fee_bps));
        let admin = self.admin.insecure_clone();
        self.send(ix, &[&admin]).expect("init_config");
    }

    // ------------------------------------------------------------------ plumbing

    pub fn mk<A: ToAccountMetas, D: InstructionData>(accounts: A, data: D) -> Instruction {
        Instruction {
            program_id: addr(&program_id()),
            accounts: accounts
                .to_account_metas(None)
                .into_iter()
                .map(|m| AccountMeta {
                    pubkey: addr(&m.pubkey),
                    is_signer: m.is_signer,
                    is_writable: m.is_writable,
                })
                .collect(),
            data: data.data(),
        }
    }

    /// First signer pays the fee. Expires the blockhash afterwards so identical retries are not deduped.
    pub fn send(&mut self, ix: Instruction, signers: &[&Keypair]) -> TransactionResult {
        let payer = signers[0].pubkey();
        let tx = Transaction::new_signed_with_payer(
            &[ix],
            Some(&payer),
            signers,
            self.svm.latest_blockhash(),
        );
        let res = self.svm.send_transaction(tx);
        self.svm.expire_blockhash();
        res
    }

    pub fn set_time(&mut self, ts: i64) {
        let mut clock: Clock = self.svm.get_sysvar();
        clock.unix_timestamp = ts;
        clock.slot += 1;
        self.svm.set_sysvar(&clock);
    }
    pub fn now(&self) -> i64 {
        self.svm.get_sysvar::<Clock>().unix_timestamp
    }

    pub fn new_user(&mut self, tokens: u64) -> User {
        let kp = Keypair::new();
        self.svm.airdrop(&kp.pubkey(), 10_000_000_000).unwrap();
        let token = self.token_account(&kp, &self.mint.clone(), tokens);
        User { kp, token }
    }

    pub fn token_account(&mut self, owner: &Keypair, mint: &Pk, tokens: u64) -> Pk {
        let ata = CreateAssociatedTokenAccount::new(&mut self.svm, &self.funder, &addr(mint))
            .owner(&owner.pubkey())
            .send()
            .unwrap();
        if tokens > 0 {
            MintTo::new(&mut self.svm, &self.funder, &addr(mint), &ata, tokens)
                .owner(&self.mint_auth)
                .send()
                .unwrap();
        }
        pk(&ata)
    }

    pub fn bal(&self, token: &Pk) -> u64 {
        let acc = self.svm.get_account(&addr(token)).expect("token account");
        u64::from_le_bytes(acc.data[64..72].try_into().unwrap())
    }

    pub fn token_owner(&self, token: &Pk) -> Pk {
        let acc = self.svm.get_account(&addr(token)).expect("token account");
        Pk::new_from_array(acc.data[32..64].try_into().unwrap())
    }

    pub fn pool_state(&self, pool: &PoolRef) -> Pool {
        let acc = self.svm.get_account(&addr(&pool.key)).expect("pool");
        Pool::try_deserialize(&mut &acc.data[..]).unwrap()
    }
    pub fn part_state(&self, pool: &PoolRef, user: &Pk) -> Participation {
        let acc = self
            .svm
            .get_account(&addr(&part_pda(&pool.key, user)))
            .expect("participation");
        Participation::try_deserialize(&mut &acc.data[..]).unwrap()
    }
    pub fn config_state(&self) -> Config {
        let acc = self.svm.get_account(&addr(&config_pda())).expect("config");
        Config::try_deserialize(&mut &acc.data[..]).unwrap()
    }

    // ------------------------------------------------------------------ instruction builders

    pub fn create_params(
        &self,
        pool_id: u64,
        mode: StakeMode,
        penalty_bps: u16,
        duration: u8,
        required: u8,
    ) -> CreatePoolParams {
        CreatePoolParams {
            pool_id,
            kind: PoolKind::Squad,
            mode,
            penalty_bps,
            start_ts: BASE + DAY,
            duration_days: duration,
            required_days: required,
            goal_hash: [7u8; 32],
            join_window_secs: 3_600,
            max_participants: 100,
        }
    }

    pub fn ix_create_pool(&self, creator: &Pk, mint: &Pk, params: CreatePoolParams) -> Instruction {
        let pool = pool_pda(creator, params.pool_id);
        Self::mk(
            vowed::accounts::CreatePool {
                creator: *creator,
                config: config_pda(),
                pool,
                mint: *mint,
                vault: vault_pda(&pool),
                token_program: anchor_spl::token::ID,
                system_program: anchor_lang::system_program::ID,
            },
            vowed::instruction::CreatePool { params },
        )
    }

    pub fn create_pool_with(&mut self, params: CreatePoolParams) -> (TransactionResult, PoolRef) {
        let funder = self.funder.insecure_clone();
        let creator = pk_of(&funder);
        let ix = self.ix_create_pool(&creator, &self.mint.clone(), params.clone());
        let key = pool_pda(&creator, params.pool_id);
        let r = self.send(ix, &[&funder]);
        (
            r,
            PoolRef {
                key,
                creator,
                id: params.pool_id,
                vault: vault_pda(&key),
                mint: self.mint,
                start_ts: params.start_ts,
                duration: params.duration_days,
            },
        )
    }

    /// Standard pool: starts BASE+1 day (UTC midnight), 1h join window.
    pub fn pool(&mut self, id: u64, mode: StakeMode, penalty_bps: u16, duration: u8, required: u8) -> PoolRef {
        let params = self.create_params(id, mode, penalty_bps, duration, required);
        let (r, p) = self.create_pool_with(params);
        r.expect("create_pool");
        p
    }
    pub fn hard_pool(&mut self, id: u64, duration: u8, required: u8) -> PoolRef {
        self.pool(id, StakeMode::Hard, 10_000, duration, required)
    }

    pub fn ix_join(&self, pool: &PoolRef, user: &Pk, user_token: &Pk, stake: u64, tz: i16) -> Instruction {
        Self::mk(
            vowed::accounts::JoinPool {
                user: *user,
                config: config_pda(),
                pool: pool.key,
                participation: part_pda(&pool.key, user),
                mint: pool.mint,
                vault: pool.vault,
                user_token: *user_token,
                token_program: anchor_spl::token::ID,
                system_program: anchor_lang::system_program::ID,
            },
            vowed::instruction::JoinPool {
                stake,
                tz_offset_minutes: tz,
                device_key_hash: [9u8; 32],
            },
        )
    }

    pub fn join(&mut self, pool: &PoolRef, user: &User, stake: u64, tz: i16) -> TransactionResult {
        let ix = self.ix_join(pool, &user.key(), &user.token, stake, tz);
        self.send(ix, &[&user.kp])
    }

    pub fn ix_checkin(&self, oracle: &Pk, pool: &PoolRef, participation: &Pk, day: u8) -> Instruction {
        Self::mk(
            vowed::accounts::RecordCheckin {
                oracle: *oracle,
                config: config_pda(),
                pool: pool.key,
                participation: *participation,
            },
            vowed::instruction::RecordCheckin { day_index: day },
        )
    }

    pub fn checkin(&mut self, pool: &PoolRef, user: &Pk, day: u8) -> TransactionResult {
        let oracle = self.oracle.insecure_clone();
        let ix = self.ix_checkin(&pk_of(&oracle), pool, &part_pda(&pool.key, user), day);
        self.send(ix, &[&oracle])
    }

    /// Move the clock to the start of `day` for a UTC user (+1h) and record it.
    pub fn checkin_on_day(&mut self, pool: &PoolRef, user: &Pk, day: u8) -> TransactionResult {
        self.set_time(pool.day_start(day as i64) + 3_600);
        self.checkin(pool, user, day)
    }

    pub fn ix_settle(&self, pool: &PoolRef, participation: &Pk) -> Instruction {
        Self::mk(
            vowed::accounts::SettleParticipation {
                pool: pool.key,
                participation: *participation,
            },
            vowed::instruction::SettleParticipation {},
        )
    }

    pub fn settle(&mut self, pool: &PoolRef, user: &Pk) -> TransactionResult {
        let payer = self.funder.insecure_clone();
        let ix = self.ix_settle(pool, &part_pda(&pool.key, user));
        self.send(ix, &[&payer])
    }

    pub fn ix_claim(&self, owner: &Pk, pool: &PoolRef, participation: &Pk, owner_token: &Pk) -> Instruction {
        Self::mk(
            vowed::accounts::Claim {
                owner: *owner,
                pool: pool.key,
                participation: *participation,
                mint: pool.mint,
                vault: pool.vault,
                owner_token: *owner_token,
                token_program: anchor_spl::token::ID,
            },
            vowed::instruction::Claim {},
        )
    }

    pub fn claim(&mut self, pool: &PoolRef, user: &User) -> TransactionResult {
        let ix = self.ix_claim(&user.key(), pool, &part_pda(&pool.key, &user.key()), &user.token);
        self.send(ix, &[&user.kp])
    }

    pub fn ix_sweep(&self, caller: &Pk, pool: &PoolRef, treasury_token: &Pk) -> Instruction {
        Self::mk(
            vowed::accounts::SweepTreasury {
                caller: *caller,
                config: config_pda(),
                pool: pool.key,
                mint: pool.mint,
                vault: pool.vault,
                treasury_token: *treasury_token,
                token_program: anchor_spl::token::ID,
            },
            vowed::instruction::SweepTreasury {},
        )
    }

    pub fn sweep(&mut self, pool: &PoolRef) -> TransactionResult {
        let payer = self.funder.insecure_clone();
        let ix = self.ix_sweep(&pk_of(&payer), pool, &self.treasury_token.clone());
        self.send(ix, &[&payer])
    }

    pub fn ix_void(&self, admin: &Pk, pool: &PoolRef) -> Instruction {
        Self::mk(
            vowed::accounts::VoidPool {
                admin: *admin,
                config: config_pda(),
                pool: pool.key,
            },
            vowed::instruction::VoidPool {},
        )
    }

    pub fn void(&mut self, pool: &PoolRef) -> TransactionResult {
        let admin = self.admin.insecure_clone();
        let ix = self.ix_void(&pk_of(&admin), pool);
        self.send(ix, &[&admin])
    }

    pub fn ix_set_paused(&self, admin: &Pk, paused: bool) -> Instruction {
        Self::mk(
            vowed::accounts::AdminOnly {
                admin: *admin,
                config: config_pda(),
            },
            vowed::instruction::SetPaused { paused },
        )
    }

    pub fn ix_update_oracle(&self, admin: &Pk, new_oracle: Pk) -> Instruction {
        Self::mk(
            vowed::accounts::AdminOnly {
                admin: *admin,
                config: config_pda(),
            },
            vowed::instruction::UpdateOracle { new_oracle },
        )
    }

    pub fn set_paused(&mut self, paused: bool) {
        let admin = self.admin.insecure_clone();
        let ix = self.ix_set_paused(&pk_of(&admin), paused);
        self.send(ix, &[&admin]).expect("set_paused");
    }

    // ------------------------------------------------------------------ scenario helpers

    /// Move to the settle time and settle everyone in order. Returns nothing; asserts success.
    pub fn settle_all(&mut self, pool: &PoolRef, users: &[&User]) {
        self.set_time(pool.settle_after());
        for u in users {
            self.settle(pool, &u.key()).expect("settle");
        }
    }

    pub fn total_in_users_and_treasury(&self, users: &[&User]) -> u64 {
        users.iter().map(|u| self.bal(&u.token)).sum::<u64>() + self.bal(&self.treasury_token)
    }
}

/// True when the failed transaction logged this Anchor error name.
pub fn failed_with(res: &TransactionResult, code: &str) -> bool {
    match res {
        Ok(_) => false,
        Err(e) => e
            .meta
            .logs
            .iter()
            .any(|l| l.contains(&format!("Error Code: {code}"))),
    }
}

#[track_caller]
pub fn assert_err(res: TransactionResult, code: &str) {
    if !failed_with(&res, code) {
        match res {
            Ok(_) => panic!("expected error {code}, but the transaction succeeded"),
            Err(e) => panic!("expected error {code}, got {:?}\nlogs:\n{}", e.err, e.meta.logs.join("\n")),
        }
    }
}

#[track_caller]
pub fn assert_fails(res: TransactionResult) {
    assert!(res.is_err(), "expected the transaction to fail");
}

pub fn status_is(p: &Pool, s: PoolStatus) -> bool {
    p.status == s
}
pub fn part_is(p: &Participation, s: ParticipationStatus) -> bool {
    p.status == s
}
