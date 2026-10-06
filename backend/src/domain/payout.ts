/** Payout math in BigInt, identical to programs/vowed/.../math.rs (tested against /shared/test-vectors/payout.json). */
const BPS = 10_000n;

export const bpsOf = (amount: bigint, bps: number): bigint => (amount * BigInt(bps)) / BPS;
export const penalty = (stake: bigint, penaltyBps: number): bigint => bpsOf(stake, penaltyBps);
export const failedRefund = (stake: bigint, penaltyBps: number): bigint => stake - penalty(stake, penaltyBps);

export function splitForfeit(totalForfeit: bigint, feeBps: number): { fee: bigint; distributable: bigint } {
  const fee = bpsOf(totalForfeit, feeBps);
  return { fee, distributable: totalForfeit - fee };
}

export function successPayout(stake: bigint, distributable: bigint, totalSuccessStake: bigint): bigint {
  if (totalSuccessStake === 0n || stake > totalSuccessStake) throw new Error("invalid success stake total");
  return stake + (distributable * stake) / totalSuccessStake;
}

/** Amount a participant can claim once the pool is Settled (or Voided: always the full stake). */
export function claimable(args: {
  poolStatus: string;
  participantStatus: string;
  stake: bigint;
  penaltyBps: number;
  distributable: bigint;
  totalSuccessStake: bigint;
}): bigint {
  if (args.participantStatus === "Claimed") return 0n;
  if (args.poolStatus === "Voided") return args.stake;
  if (args.poolStatus !== "Settled") return 0n;
  if (args.participantStatus === "Succeeded") return successPayout(args.stake, args.distributable, args.totalSuccessStake);
  if (args.participantStatus === "Failed") return failedRefund(args.stake, args.penaltyBps);
  return 0n;
}
