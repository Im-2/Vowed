use anchor_lang::prelude::*;

#[error_code]
pub enum VowedError {
    #[msg("Signer is not authorized for this instruction")]
    Unauthorized,
    #[msg("Signer is not the program upgrade authority")]
    NotUpgradeAuthority,
    #[msg("The program is paused")]
    Paused,
    #[msg("Invalid configuration value")]
    InvalidConfig,
    #[msg("Mint is not on the allowed list")]
    MintNotAllowed,
    #[msg("Mint uses token extensions, which are not supported")]
    MintHasExtensions,
    #[msg("Invalid pool parameters")]
    InvalidPoolParams,
    #[msg("Pool is not in a state that allows this action")]
    InvalidPoolStatus,
    #[msg("The join window is closed")]
    JoinClosed,
    #[msg("Pool is full")]
    PoolFull,
    #[msg("Stake must be greater than zero")]
    StakeZero,
    #[msg("Stake exceeds the maximum allowed")]
    StakeTooLarge,
    #[msg("Timezone offset out of range")]
    InvalidTimezone,
    #[msg("Day index is out of range")]
    DayOutOfRange,
    #[msg("Check-in is outside the window for that day")]
    OutsideCheckinWindow,
    #[msg("Day already recorded")]
    DuplicateCheckin,
    #[msg("Participation is not active")]
    ParticipationNotActive,
    #[msg("Challenge has not ended yet")]
    NotEnded,
    #[msg("Participation already settled")]
    AlreadySettled,
    #[msg("Pool is not settled yet")]
    NotSettled,
    #[msg("Already claimed")]
    AlreadyClaimed,
    #[msg("Nothing to claim")]
    NothingToClaim,
    #[msg("Nothing to sweep")]
    NothingToSweep,
    #[msg("Claims are still pending")]
    ClaimsPending,
    #[msg("Pool already has payouts and cannot be voided")]
    CannotVoid,
    #[msg("Arithmetic overflow")]
    MathOverflow,
    #[msg("Account does not belong to this pool")]
    WrongPool,
}
