use anchor_lang::prelude::*;
use anchor_spl::token_interface::{self, Mint, TokenAccount, TokenInterface, TransferChecked};

use crate::{constants::*, error::VowedError, events::*, math, state::*};

// ---------------------------------------------------------------- record_checkin

#[derive(Accounts)]
pub struct RecordCheckin<'info> {
    pub oracle: Signer<'info>,
    #[account(
        seeds = [CONFIG_SEED],
        bump = config.bump,
        has_one = oracle @ VowedError::Unauthorized
    )]
    pub config: Account<'info, Config>,
    #[account(
        mut,
        seeds = [POOL_SEED, pool.creator.as_ref(), &pool.id.to_le_bytes()],
        bump = pool.bump
    )]
    pub pool: Account<'info, Pool>,
    #[account(
        mut,
        seeds = [PARTICIPATION_SEED, pool.key().as_ref(), participation.user.as_ref()],
        bump = participation.bump,
        constraint = participation.pool == pool.key() @ VowedError::WrongPool
    )]
    pub participation: Account<'info, Participation>,
}

pub fn handle_record_checkin(ctx: Context<RecordCheckin>, day_index: u8) -> Result<()> {
    let now = Clock::get()?.unix_timestamp;
    let pool = &ctx.accounts.pool;
    let p = &mut ctx.accounts.participation;

    require!(pool.status == PoolStatus::Open, VowedError::InvalidPoolStatus);
    require!(now >= pool.start_ts, VowedError::OutsideCheckinWindow);
    // Check-ins and settlement never overlap in time.
    require!(now < pool.settle_after_ts, VowedError::OutsideCheckinWindow);
    require!(p.status == ParticipationStatus::Active, VowedError::ParticipationNotActive);
    require!(day_index < pool.duration_days, VowedError::DayOutOfRange);
    require!(
        math::checkin_window_ok(now, pool.start_ts, p.tz_offset_minutes, day_index),
        VowedError::OutsideCheckinWindow
    );

    let bit = 1u64 << day_index;
    require!(p.checkin_bitmap & bit == 0, VowedError::DuplicateCheckin);
    p.checkin_bitmap |= bit;
    p.days_completed = p
        .days_completed
        .checked_add(1)
        .ok_or(VowedError::MathOverflow)?;

    emit!(CheckinRecorded {
        pool: pool.key(),
        user: p.user,
        day_index,
        days_completed: p.days_completed,
    });
    Ok(())
}

// ---------------------------------------------------------------- settle_participation

#[derive(Accounts)]
pub struct SettleParticipation<'info> {
    #[account(
        mut,
        seeds = [POOL_SEED, pool.creator.as_ref(), &pool.id.to_le_bytes()],
        bump = pool.bump
    )]
    pub pool: Account<'info, Pool>,
    #[account(
        mut,
        seeds = [PARTICIPATION_SEED, pool.key().as_ref(), participation.user.as_ref()],
        bump = participation.bump,
        constraint = participation.pool == pool.key() @ VowedError::WrongPool
    )]
    pub participation: Account<'info, Participation>,
}

/// Permissionless: anyone (a crank, a friend, the user) can settle once the grace period is over.
pub fn handle_settle_participation(ctx: Context<SettleParticipation>) -> Result<()> {
    let now = Clock::get()?.unix_timestamp;
    let pool = &mut ctx.accounts.pool;
    let p = &mut ctx.accounts.participation;

    require!(
        matches!(pool.status, PoolStatus::Open | PoolStatus::Settling),
        VowedError::InvalidPoolStatus
    );
    require!(now >= pool.settle_after_ts, VowedError::NotEnded);
    require!(p.status == ParticipationStatus::Active, VowedError::AlreadySettled);

    let succeeded = p.days_completed >= pool.required_days;
    let mut penalty = 0u64;
    if succeeded {
        p.status = ParticipationStatus::Succeeded;
        pool.total_success_stake = pool
            .total_success_stake
            .checked_add(p.stake)
            .ok_or(VowedError::MathOverflow)?;
        pool.pending_claims = pool.pending_claims.checked_add(1).ok_or(VowedError::MathOverflow)?;
    } else {
        penalty = math::penalty(p.stake, pool.penalty_bps).ok_or(VowedError::MathOverflow)?;
        p.status = ParticipationStatus::Failed;
        pool.total_forfeit = pool
            .total_forfeit
            .checked_add(penalty)
            .ok_or(VowedError::MathOverflow)?;
        // A hard-mode failure has nothing to claim, so it must not block the treasury sweep.
        if p.stake.checked_sub(penalty).ok_or(VowedError::MathOverflow)? > 0 {
            pool.pending_claims = pool.pending_claims.checked_add(1).ok_or(VowedError::MathOverflow)?;
        }
    }
    pool.settled_count = pool.settled_count.checked_add(1).ok_or(VowedError::MathOverflow)?;
    pool.status = PoolStatus::Settling;

    emit!(ParticipationSettled {
        pool: pool.key(),
        user: p.user,
        succeeded,
        penalty,
    });

    if pool.settled_count == pool.participant_count {
        let (fee, distributable) =
            math::split_forfeit(pool.total_forfeit, pool.fee_bps).ok_or(VowedError::MathOverflow)?;
        pool.fee_amount = fee;
        pool.distributable = distributable;
        pool.status = PoolStatus::Settled;
        emit!(PoolSettled {
            pool: pool.key(),
            total_forfeit: pool.total_forfeit,
            fee,
            distributable,
            total_success_stake: pool.total_success_stake,
        });
    }
    Ok(())
}

// ---------------------------------------------------------------- claim

#[derive(Accounts)]
pub struct Claim<'info> {
    pub owner: Signer<'info>,
    #[account(
        mut,
        seeds = [POOL_SEED, pool.creator.as_ref(), &pool.id.to_le_bytes()],
        bump = pool.bump
    )]
    pub pool: Account<'info, Pool>,
    #[account(
        mut,
        seeds = [PARTICIPATION_SEED, pool.key().as_ref(), owner.key().as_ref()],
        bump = participation.bump,
        constraint = participation.pool == pool.key() @ VowedError::WrongPool,
        constraint = participation.user == owner.key() @ VowedError::Unauthorized
    )]
    pub participation: Account<'info, Participation>,
    #[account(address = pool.mint @ VowedError::MintNotAllowed)]
    pub mint: InterfaceAccount<'info, Mint>,
    #[account(mut, address = pool.vault @ VowedError::WrongPool)]
    pub vault: InterfaceAccount<'info, TokenAccount>,
    #[account(
        mut,
        token::mint = mint,
        token::authority = owner,
        token::token_program = token_program
    )]
    pub owner_token: InterfaceAccount<'info, TokenAccount>,
    pub token_program: Interface<'info, TokenInterface>,
}

pub fn handle_claim(ctx: Context<Claim>) -> Result<()> {
    let pool = &mut ctx.accounts.pool;
    let p = &mut ctx.accounts.participation;

    let payout = match pool.status {
        PoolStatus::Voided => match p.status {
            ParticipationStatus::Claimed => return err!(VowedError::AlreadyClaimed),
            _ => p.stake,
        },
        PoolStatus::Settled => match p.status {
            ParticipationStatus::Succeeded => {
                math::success_payout(p.stake, pool.distributable, pool.total_success_stake)
                    .ok_or(VowedError::MathOverflow)?
            }
            ParticipationStatus::Failed => {
                math::failed_refund(p.stake, pool.penalty_bps).ok_or(VowedError::MathOverflow)?
            }
            ParticipationStatus::Claimed => return err!(VowedError::AlreadyClaimed),
            ParticipationStatus::Active => return err!(VowedError::NotSettled),
        },
        _ => return err!(VowedError::NotSettled),
    };
    require!(payout > 0, VowedError::NothingToClaim);

    p.status = ParticipationStatus::Claimed;
    pool.paid_out = pool.paid_out.checked_add(payout).ok_or(VowedError::MathOverflow)?;
    // Vault accounting invariant: we can never pay out more than was deposited.
    require!(pool.paid_out <= pool.total_deposits, VowedError::MathOverflow);
    pool.claimed_count = pool.claimed_count.checked_add(1).ok_or(VowedError::MathOverflow)?;
    pool.pending_claims = pool.pending_claims.checked_sub(1).ok_or(VowedError::MathOverflow)?;

    let creator = pool.creator;
    let id_bytes = pool.id.to_le_bytes();
    let bump = [pool.bump];
    let signer_seeds: &[&[&[u8]]] = &[&[POOL_SEED, creator.as_ref(), &id_bytes, &bump]];
    token_interface::transfer_checked(
        CpiContext::new_with_signer(
            ctx.accounts.token_program.key(),
            TransferChecked {
                from: ctx.accounts.vault.to_account_info(),
                mint: ctx.accounts.mint.to_account_info(),
                to: ctx.accounts.owner_token.to_account_info(),
                authority: pool.to_account_info(),
            },
            signer_seeds,
        ),
        payout,
        ctx.accounts.mint.decimals,
    )?;

    emit!(Claimed {
        pool: pool.key(),
        user: p.user,
        amount: payout,
    });
    Ok(())
}

// ---------------------------------------------------------------- sweep_treasury

#[derive(Accounts)]
pub struct SweepTreasury<'info> {
    pub caller: Signer<'info>,
    #[account(seeds = [CONFIG_SEED], bump = config.bump)]
    pub config: Account<'info, Config>,
    #[account(
        seeds = [POOL_SEED, pool.creator.as_ref(), &pool.id.to_le_bytes()],
        bump = pool.bump
    )]
    pub pool: Account<'info, Pool>,
    #[account(address = pool.mint @ VowedError::MintNotAllowed)]
    pub mint: InterfaceAccount<'info, Mint>,
    #[account(mut, address = pool.vault @ VowedError::WrongPool)]
    pub vault: InterfaceAccount<'info, TokenAccount>,
    #[account(
        mut,
        token::mint = mint,
        token::authority = config.treasury,
        token::token_program = token_program
    )]
    pub treasury_token: InterfaceAccount<'info, TokenAccount>,
    pub token_program: Interface<'info, TokenInterface>,
}

/// Sends whatever is left in the vault (fee, forfeits nobody can claim, rounding dust) to the treasury.
/// Only after every non-zero claim has been made, so it can never take a participant's money.
pub fn handle_sweep_treasury(ctx: Context<SweepTreasury>) -> Result<()> {
    let pool = &ctx.accounts.pool;
    require!(
        matches!(pool.status, PoolStatus::Settled | PoolStatus::Voided),
        VowedError::NotSettled
    );
    require!(pool.pending_claims == 0, VowedError::ClaimsPending);
    let amount = ctx.accounts.vault.amount;
    require!(amount > 0, VowedError::NothingToSweep);

    let creator = pool.creator;
    let id_bytes = pool.id.to_le_bytes();
    let bump = [pool.bump];
    let signer_seeds: &[&[&[u8]]] = &[&[POOL_SEED, creator.as_ref(), &id_bytes, &bump]];
    token_interface::transfer_checked(
        CpiContext::new_with_signer(
            ctx.accounts.token_program.key(),
            TransferChecked {
                from: ctx.accounts.vault.to_account_info(),
                mint: ctx.accounts.mint.to_account_info(),
                to: ctx.accounts.treasury_token.to_account_info(),
                authority: ctx.accounts.pool.to_account_info(),
            },
            signer_seeds,
        ),
        amount,
        ctx.accounts.mint.decimals,
    )?;

    emit!(TreasurySwept {
        pool: pool.key(),
        amount,
    });
    Ok(())
}
