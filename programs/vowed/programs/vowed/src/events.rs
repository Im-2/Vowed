use anchor_lang::prelude::*;

#[event]
pub struct ConfigInitialized {
    pub admin: Pubkey,
    pub oracle: Pubkey,
    pub treasury: Pubkey,
}

#[event]
pub struct PoolCreated {
    pub pool: Pubkey,
    pub creator: Pubkey,
    pub mint: Pubkey,
    pub pool_id: u64,
    pub start_ts: i64,
    pub end_ts: i64,
    pub duration_days: u8,
    pub required_days: u8,
    pub penalty_bps: u16,
    pub goal_hash: [u8; 32],
    /// DEMO POOL flag and day length (86_400 for normal pools).
    pub is_demo: bool,
    pub day_secs: u32,
}

#[event]
pub struct PoolJoined {
    pub pool: Pubkey,
    pub user: Pubkey,
    pub stake: u64,
    pub tz_offset_minutes: i16,
}

#[event]
pub struct CheckinRecorded {
    pub pool: Pubkey,
    pub user: Pubkey,
    pub day_index: u8,
    pub days_completed: u8,
}

#[event]
pub struct ParticipationSettled {
    pub pool: Pubkey,
    pub user: Pubkey,
    pub succeeded: bool,
    pub penalty: u64,
}

#[event]
pub struct PoolSettled {
    pub pool: Pubkey,
    pub total_forfeit: u64,
    pub fee: u64,
    pub distributable: u64,
    pub total_success_stake: u64,
}

#[event]
pub struct Claimed {
    pub pool: Pubkey,
    pub user: Pubkey,
    pub amount: u64,
}

#[event]
pub struct PoolVoided {
    pub pool: Pubkey,
    pub admin: Pubkey,
}

#[event]
pub struct TreasurySwept {
    pub pool: Pubkey,
    pub amount: u64,
}

#[event]
pub struct DemoEnabledChanged {
    pub enabled: bool,
}

#[event]
pub struct PausedChanged {
    pub paused: bool,
}

#[event]
pub struct OracleUpdated {
    pub old_oracle: Pubkey,
    pub new_oracle: Pubkey,
}
