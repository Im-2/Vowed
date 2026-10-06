pub mod constants;
pub mod error;
pub mod events;
pub mod instructions;
pub mod math;
pub mod state;

use anchor_lang::prelude::*;

pub use constants::*;
pub use instructions::*;
pub use state::*;

declare_id!("BMTXJRZ4QxzCg4UCHKo6qGGiGXKW26ARPAtPaXA8k7EL");

#[program]
pub mod vowed {
    use super::*;

    pub fn init_config(ctx: Context<InitConfig>, params: InitConfigParams) -> Result<()> {
        instructions::admin::handle_init_config(ctx, params)
    }

    pub fn set_paused(ctx: Context<AdminOnly>, paused: bool) -> Result<()> {
        instructions::admin::handle_set_paused(ctx, paused)
    }

    pub fn set_demo_enabled(ctx: Context<AdminOnly>, enabled: bool) -> Result<()> {
        instructions::admin::handle_set_demo_enabled(ctx, enabled)
    }

    pub fn update_oracle(ctx: Context<AdminOnly>, new_oracle: Pubkey) -> Result<()> {
        instructions::admin::handle_update_oracle(ctx, new_oracle)
    }

    pub fn create_pool(ctx: Context<CreatePool>, params: CreatePoolParams) -> Result<()> {
        instructions::pool::handle_create_pool(ctx, params)
    }

    pub fn join_pool(
        ctx: Context<JoinPool>,
        stake: u64,
        tz_offset_minutes: i16,
        device_key_hash: [u8; 32],
    ) -> Result<()> {
        instructions::pool::handle_join_pool(ctx, stake, tz_offset_minutes, device_key_hash)
    }

    pub fn record_checkin(ctx: Context<RecordCheckin>, day_index: u8) -> Result<()> {
        instructions::play::handle_record_checkin(ctx, day_index)
    }

    pub fn settle_participation(ctx: Context<SettleParticipation>) -> Result<()> {
        instructions::play::handle_settle_participation(ctx)
    }

    pub fn claim(ctx: Context<Claim>) -> Result<()> {
        instructions::play::handle_claim(ctx)
    }

    pub fn sweep_treasury(ctx: Context<SweepTreasury>) -> Result<()> {
        instructions::play::handle_sweep_treasury(ctx)
    }

    pub fn void_pool(ctx: Context<VoidPool>) -> Result<()> {
        instructions::pool::handle_void_pool(ctx)
    }
}
