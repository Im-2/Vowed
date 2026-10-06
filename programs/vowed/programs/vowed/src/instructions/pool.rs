use anchor_lang::prelude::*;
use anchor_spl::token_interface::{self, Mint, TokenAccount, TokenInterface, TransferChecked};

use crate::{constants::*, error::VowedError, events::*, state::*};

#[derive(AnchorSerialize, AnchorDeserialize, Clone)]
pub struct CreatePoolParams {
    /// Chosen by the creator; part of the pool PDA seeds (so nobody can squat someone else's id).
    pub pool_id: u64,
    pub kind: PoolKind,
    pub mode: StakeMode,
    pub penalty_bps: u16,
    pub start_ts: i64,
    pub duration_days: u8,
    pub required_days: u8,
    /// Hash of the GoalPlan JSON. Stored so the UI and indexer can show what was agreed.
    pub goal_hash: [u8; 32],
    pub join_window_secs: i64,
    pub max_participants: u32,
}

#[derive(Accounts)]
#[instruction(params: CreatePoolParams)]
pub struct CreatePool<'info> {
    #[account(mut)]
    pub creator: Signer<'info>,
    #[account(seeds = [CONFIG_SEED], bump = config.bump)]
    pub config: Account<'info, Config>,
    #[account(
        init,
        payer = creator,
        space = 8 + Pool::INIT_SPACE,
        seeds = [POOL_SEED, creator.key().as_ref(), &params.pool_id.to_le_bytes()],
        bump
    )]
    pub pool: Account<'info, Pool>,
    pub mint: InterfaceAccount<'info, Mint>,
    /// Program-owned vault: a token account at a PDA whose authority is the pool PDA.
    #[account(
        init,
        payer = creator,
        seeds = [VAULT_SEED, pool.key().as_ref()],
        bump,
        token::mint = mint,
        token::authority = pool,
        token::token_program = token_program
    )]
    pub vault: InterfaceAccount<'info, TokenAccount>,
    pub token_program: Interface<'info, TokenInterface>,
    pub system_program: Program<'info, System>,
}

pub fn handle_create_pool(ctx: Context<CreatePool>, params: CreatePoolParams) -> Result<()> {
    let config = &ctx.accounts.config;
    require!(!config.paused, VowedError::Paused);

    let mint = &ctx.accounts.mint;
    require!(config.is_mint_allowed(&mint.key()), VowedError::MintNotAllowed);
    require_keys_eq!(
        *mint.to_account_info().owner,
        ctx.accounts.token_program.key(),
        VowedError::MintNotAllowed
    );
    require!(
        mint.to_account_info().data_len() == PLAIN_MINT_LEN,
        VowedError::MintHasExtensions
    );

    let now = Clock::get()?.unix_timestamp;
    require!(
        (1..=MAX_DURATION_DAYS).contains(&params.duration_days),
        VowedError::InvalidPoolParams
    );
    require!(
        params.required_days >= 1 && params.required_days <= params.duration_days,
        VowedError::InvalidPoolParams
    );
    require!(params.start_ts > now, VowedError::InvalidPoolParams);
    require!(
        params.start_ts <= now.checked_add(MAX_START_AHEAD_SECS).ok_or(VowedError::MathOverflow)?,
        VowedError::InvalidPoolParams
    );
    require!(
        (0..=MAX_JOIN_WINDOW_SECS).contains(&params.join_window_secs),
        VowedError::InvalidPoolParams
    );
    require!(
        (1..=MAX_PARTICIPANTS_LIMIT).contains(&params.max_participants),
        VowedError::InvalidPoolParams
    );
    match params.mode {
        StakeMode::Hard => require!(
            params.penalty_bps == HARD_PENALTY_BPS,
            VowedError::InvalidPoolParams
        ),
        StakeMode::Soft => require!(
            (1..=MAX_SOFT_PENALTY_BPS).contains(&params.penalty_bps),
            VowedError::InvalidPoolParams
        ),
    }

    let duration_secs = (params.duration_days as i64)
        .checked_mul(SECONDS_PER_DAY)
        .ok_or(VowedError::MathOverflow)?;
    let end_ts = params
        .start_ts
        .checked_add(duration_secs)
        .ok_or(VowedError::MathOverflow)?;

    let pool = &mut ctx.accounts.pool;
    pool.id = params.pool_id;
    pool.creator = ctx.accounts.creator.key();
    pool.mint = mint.key();
    pool.vault = ctx.accounts.vault.key();
    pool.kind = params.kind;
    pool.mode = params.mode;
    pool.penalty_bps = params.penalty_bps;
    pool.fee_bps = config.fee_bps;
    pool.start_ts = params.start_ts;
    pool.end_ts = end_ts;
    pool.join_deadline_ts = params
        .start_ts
        .checked_add(params.join_window_secs)
        .ok_or(VowedError::MathOverflow)?;
    pool.settle_after_ts = end_ts
        .checked_add(config.settle_grace_secs)
        .ok_or(VowedError::MathOverflow)?;
    pool.duration_days = params.duration_days;
    pool.required_days = params.required_days;
    pool.goal_hash = params.goal_hash;
    pool.max_participants = params.max_participants;
    pool.participant_count = 0;
    pool.settled_count = 0;
    pool.pending_claims = 0;
    pool.claimed_count = 0;
    pool.total_deposits = 0;
    pool.total_forfeit = 0;
    pool.total_success_stake = 0;
    pool.fee_amount = 0;
    pool.distributable = 0;
    pool.paid_out = 0;
    pool.status = PoolStatus::Open;
    pool.bump = ctx.bumps.pool;
    pool.vault_bump = ctx.bumps.vault;

    emit!(PoolCreated {
        pool: pool.key(),
        creator: pool.creator,
        mint: pool.mint,
        pool_id: pool.id,
        start_ts: pool.start_ts,
        end_ts: pool.end_ts,
        duration_days: pool.duration_days,
        required_days: pool.required_days,
        penalty_bps: pool.penalty_bps,
        goal_hash: pool.goal_hash,
    });
    Ok(())
}

#[derive(Accounts)]
pub struct JoinPool<'info> {
    #[account(mut)]
    pub user: Signer<'info>,
    #[account(seeds = [CONFIG_SEED], bump = config.bump)]
    pub config: Account<'info, Config>,
    #[account(
        mut,
        seeds = [POOL_SEED, pool.creator.as_ref(), &pool.id.to_le_bytes()],
        bump = pool.bump
    )]
    pub pool: Account<'info, Pool>,
    #[account(
        init,
        payer = user,
        space = 8 + Participation::INIT_SPACE,
        seeds = [PARTICIPATION_SEED, pool.key().as_ref(), user.key().as_ref()],
        bump
    )]
    pub participation: Account<'info, Participation>,
    #[account(address = pool.mint @ VowedError::MintNotAllowed)]
    pub mint: InterfaceAccount<'info, Mint>,
    #[account(mut, address = pool.vault @ VowedError::WrongPool)]
    pub vault: InterfaceAccount<'info, TokenAccount>,
    #[account(
        mut,
        token::mint = mint,
        token::authority = user,
        token::token_program = token_program
    )]
    pub user_token: InterfaceAccount<'info, TokenAccount>,
    pub token_program: Interface<'info, TokenInterface>,
    pub system_program: Program<'info, System>,
}

pub fn handle_join_pool(
    ctx: Context<JoinPool>,
    stake: u64,
    tz_offset_minutes: i16,
    device_key_hash: [u8; 32],
) -> Result<()> {
    let config = &ctx.accounts.config;
    require!(!config.paused, VowedError::Paused);
    require!(stake > 0, VowedError::StakeZero);
    require!(stake <= config.max_stake, VowedError::StakeTooLarge);
    require!(
        (MIN_TZ_OFFSET_MINUTES..=MAX_TZ_OFFSET_MINUTES).contains(&tz_offset_minutes),
        VowedError::InvalidTimezone
    );
    require_keys_eq!(
        *ctx.accounts.mint.to_account_info().owner,
        ctx.accounts.token_program.key(),
        VowedError::MintNotAllowed
    );

    let now = Clock::get()?.unix_timestamp;
    let pool = &mut ctx.accounts.pool;
    require!(pool.status == PoolStatus::Open, VowedError::InvalidPoolStatus);
    require!(now <= pool.join_deadline_ts, VowedError::JoinClosed);
    require!(pool.participant_count < pool.max_participants, VowedError::PoolFull);

    pool.participant_count = pool
        .participant_count
        .checked_add(1)
        .ok_or(VowedError::MathOverflow)?;
    pool.total_deposits = pool
        .total_deposits
        .checked_add(stake)
        .ok_or(VowedError::MathOverflow)?;

    let p = &mut ctx.accounts.participation;
    p.pool = pool.key();
    p.user = ctx.accounts.user.key();
    p.stake = stake;
    p.tz_offset_minutes = tz_offset_minutes;
    p.checkin_bitmap = 0;
    p.days_completed = 0;
    p.status = ParticipationStatus::Active;
    p.device_key_hash = device_key_hash;
    p.bump = ctx.bumps.participation;

    token_interface::transfer_checked(
        CpiContext::new(
            ctx.accounts.token_program.key(),
            TransferChecked {
                from: ctx.accounts.user_token.to_account_info(),
                mint: ctx.accounts.mint.to_account_info(),
                to: ctx.accounts.vault.to_account_info(),
                authority: ctx.accounts.user.to_account_info(),
            },
        ),
        stake,
        ctx.accounts.mint.decimals,
    )?;

    emit!(PoolJoined {
        pool: pool.key(),
        user: p.user,
        stake,
        tz_offset_minutes,
    });
    Ok(())
}

#[derive(Accounts)]
pub struct VoidPool<'info> {
    pub admin: Signer<'info>,
    #[account(
        seeds = [CONFIG_SEED],
        bump = config.bump,
        has_one = admin @ VowedError::Unauthorized
    )]
    pub config: Account<'info, Config>,
    #[account(
        mut,
        seeds = [POOL_SEED, pool.creator.as_ref(), &pool.id.to_le_bytes()],
        bump = pool.bump
    )]
    pub pool: Account<'info, Pool>,
}

/// Escape hatch if the oracle failed or a bug is found. Only allowed before any payout happened,
/// so refunds can never exceed what the vault holds. This is a documented centralisation trade-off.
pub fn handle_void_pool(ctx: Context<VoidPool>) -> Result<()> {
    let pool = &mut ctx.accounts.pool;
    require!(
        matches!(pool.status, PoolStatus::Open | PoolStatus::Settling),
        VowedError::InvalidPoolStatus
    );
    require!(pool.claimed_count == 0 && pool.paid_out == 0, VowedError::CannotVoid);
    pool.status = PoolStatus::Voided;
    pool.pending_claims = pool.participant_count;
    emit!(PoolVoided {
        pool: pool.key(),
        admin: ctx.accounts.admin.key(),
    });
    Ok(())
}
