use anchor_lang::prelude::*;

use crate::constants::MAX_ALLOWED_MINTS;

#[account]
#[derive(InitSpace)]
pub struct Config {
    pub admin: Pubkey,
    /// Only this key may call `record_checkin`.
    pub oracle: Pubkey,
    /// Owner of the token accounts that receive fees and dust.
    pub treasury: Pubkey,
    pub fee_bps: u16,
    pub paused: bool,
    pub max_stake: u64,
    /// Pools may settle this long after end_ts (covers timezone spread and the last check-in windows).
    pub settle_grace_secs: i64,
    pub allowed_mints: [Pubkey; MAX_ALLOWED_MINTS],
    pub allowed_mint_count: u8,
    pub bump: u8,
}

impl Config {
    pub fn is_mint_allowed(&self, mint: &Pubkey) -> bool {
        self.allowed_mints[..self.allowed_mint_count as usize].contains(mint)
    }
}

#[derive(AnchorSerialize, AnchorDeserialize, Clone, Copy, PartialEq, Eq, InitSpace, Debug)]
pub enum PoolKind {
    Squad,
    Open,
}

/// Practice mode never touches the chain, so it is not an option here.
#[derive(AnchorSerialize, AnchorDeserialize, Clone, Copy, PartialEq, Eq, InitSpace, Debug)]
pub enum StakeMode {
    Soft,
    Hard,
}

/// `Active` (start_ts <= now < end_ts) is derived from the clock and never stored.
#[derive(AnchorSerialize, AnchorDeserialize, Clone, Copy, PartialEq, Eq, InitSpace, Debug)]
pub enum PoolStatus {
    /// Joinable, then running. Check-ins are accepted while Open.
    Open,
    /// At least one participation settled, not all.
    Settling,
    /// All participations settled; payouts are claimable.
    Settled,
    /// Admin voided the pool; everyone can reclaim their full stake.
    Voided,
}

#[derive(AnchorSerialize, AnchorDeserialize, Clone, Copy, PartialEq, Eq, InitSpace, Debug)]
pub enum ParticipationStatus {
    Active,
    Succeeded,
    Failed,
    Claimed,
}

#[account]
#[derive(InitSpace)]
pub struct Pool {
    pub id: u64,
    pub creator: Pubkey,
    pub mint: Pubkey,
    pub vault: Pubkey,
    pub kind: PoolKind,
    pub mode: StakeMode,
    pub penalty_bps: u16,
    /// Snapshot of config.fee_bps at creation, so later config changes cannot alter this pool's rules.
    pub fee_bps: u16,
    pub start_ts: i64,
    pub end_ts: i64,
    pub join_deadline_ts: i64,
    /// end_ts + config.settle_grace_secs at creation.
    pub settle_after_ts: i64,
    pub duration_days: u8,
    pub required_days: u8,
    pub goal_hash: [u8; 32],
    pub max_participants: u32,
    pub participant_count: u32,
    pub settled_count: u32,
    /// Participants who still have a non-zero amount to claim.
    pub pending_claims: u32,
    pub claimed_count: u32,
    pub total_deposits: u64,
    pub total_forfeit: u64,
    pub total_success_stake: u64,
    /// Set when the pool becomes Settled.
    pub fee_amount: u64,
    pub distributable: u64,
    pub paid_out: u64,
    pub status: PoolStatus,
    pub bump: u8,
    pub vault_bump: u8,
}

#[account]
#[derive(InitSpace)]
pub struct Participation {
    pub pool: Pubkey,
    pub user: Pubkey,
    pub stake: u64,
    pub tz_offset_minutes: i16,
    pub checkin_bitmap: u64,
    pub days_completed: u8,
    pub status: ParticipationStatus,
    /// Hash of the registered device key (SPEC 7.3). Informational onchain; the backend verifies it.
    pub device_key_hash: [u8; 32],
    pub bump: u8,
}
