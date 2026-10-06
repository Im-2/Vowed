use anchor_lang::prelude::*;

use crate::{constants::*, error::VowedError, events::*, state::*};

#[derive(AnchorSerialize, AnchorDeserialize, Clone)]
pub struct InitConfigParams {
    pub oracle: Pubkey,
    pub treasury: Pubkey,
    pub fee_bps: u16,
    pub max_stake: u64,
    pub settle_grace_secs: i64,
    pub allowed_mints: Vec<Pubkey>,
    /// Demo pools (minutes-long days, test money only). Leave disabled on any network where real money is staked.
    pub demo_enabled: bool,
    /// Per-participant stake cap for demo pools; must not exceed `max_stake`.
    pub demo_max_stake: u64,
    /// Subset of `allowed_mints` that demo pools may use.
    pub demo_mints: Vec<Pubkey>,
}

/// Only the program's upgrade authority can initialise the config, so nobody can front-run the
/// deployer and make themselves admin.
#[derive(Accounts)]
pub struct InitConfig<'info> {
    #[account(mut)]
    pub admin: Signer<'info>,
    #[account(
        init,
        payer = admin,
        space = 8 + Config::INIT_SPACE,
        seeds = [CONFIG_SEED],
        bump
    )]
    pub config: Account<'info, Config>,
    #[account(constraint = program.programdata_address()? == Some(program_data.key()) @ VowedError::NotUpgradeAuthority)]
    pub program: Program<'info, crate::program::Vowed>,
    #[account(constraint = program_data.upgrade_authority_address == Some(admin.key()) @ VowedError::NotUpgradeAuthority)]
    pub program_data: Account<'info, ProgramData>,
    pub system_program: Program<'info, System>,
}

pub fn handle_init_config(ctx: Context<InitConfig>, params: InitConfigParams) -> Result<()> {
    require!(params.fee_bps <= MAX_FEE_BPS, VowedError::InvalidConfig);
    require!(params.max_stake > 0, VowedError::InvalidConfig);
    require!(
        (0..=MAX_SETTLE_GRACE_SECS).contains(&params.settle_grace_secs),
        VowedError::InvalidConfig
    );
    let n = params.allowed_mints.len();
    require!((1..=MAX_ALLOWED_MINTS).contains(&n), VowedError::InvalidConfig);
    for (i, m) in params.allowed_mints.iter().enumerate() {
        require!(*m != Pubkey::default(), VowedError::InvalidConfig);
        require!(!params.allowed_mints[..i].contains(m), VowedError::InvalidConfig);
    }
    // Demo settings may only be stricter than the normal ones, never looser.
    require!(params.demo_max_stake <= params.max_stake, VowedError::InvalidConfig);
    for m in &params.demo_mints {
        require!(params.allowed_mints.contains(m), VowedError::InvalidConfig);
    }
    if params.demo_enabled {
        require!(params.demo_max_stake > 0 && !params.demo_mints.is_empty(), VowedError::InvalidConfig);
    }
    require!(params.oracle != Pubkey::default(), VowedError::InvalidConfig);
    require!(params.treasury != Pubkey::default(), VowedError::InvalidConfig);

    let config = &mut ctx.accounts.config;
    config.admin = ctx.accounts.admin.key();
    config.oracle = params.oracle;
    config.treasury = params.treasury;
    config.fee_bps = params.fee_bps;
    config.paused = false;
    config.max_stake = params.max_stake;
    config.settle_grace_secs = params.settle_grace_secs;
    config.allowed_mints = [Pubkey::default(); MAX_ALLOWED_MINTS];
    config.allowed_mints[..n].copy_from_slice(&params.allowed_mints);
    config.allowed_mint_count = n as u8;
    config.demo_enabled = params.demo_enabled;
    config.demo_max_stake = params.demo_max_stake;
    config.demo_mints = [false; MAX_ALLOWED_MINTS];
    for (i, m) in params.allowed_mints.iter().enumerate() {
        config.demo_mints[i] = params.demo_mints.contains(m);
    }
    config.bump = ctx.bumps.config;

    emit!(ConfigInitialized {
        admin: config.admin,
        oracle: config.oracle,
        treasury: config.treasury,
    });
    Ok(())
}

#[derive(Accounts)]
pub struct AdminOnly<'info> {
    pub admin: Signer<'info>,
    #[account(
        mut,
        seeds = [CONFIG_SEED],
        bump = config.bump,
        has_one = admin @ VowedError::Unauthorized
    )]
    pub config: Account<'info, Config>,
}

pub fn handle_set_paused(ctx: Context<AdminOnly>, paused: bool) -> Result<()> {
    ctx.accounts.config.paused = paused;
    emit!(PausedChanged { paused });
    Ok(())
}

/// Turns creation of NEW demo pools on or off. Existing demo pools are unaffected so nobody's stake is stranded.
pub fn handle_set_demo_enabled(ctx: Context<AdminOnly>, enabled: bool) -> Result<()> {
    let config = &mut ctx.accounts.config;
    if enabled {
        require!(config.demo_max_stake > 0 && config.demo_mints.iter().any(|d| *d), VowedError::InvalidConfig);
    }
    config.demo_enabled = enabled;
    emit!(DemoEnabledChanged { enabled });
    Ok(())
}

pub fn handle_update_oracle(ctx: Context<AdminOnly>, new_oracle: Pubkey) -> Result<()> {
    require!(new_oracle != Pubkey::default(), VowedError::InvalidConfig);
    let old_oracle = ctx.accounts.config.oracle;
    ctx.accounts.config.oracle = new_oracle;
    emit!(OracleUpdated {
        old_oracle,
        new_oracle
    });
    Ok(())
}
