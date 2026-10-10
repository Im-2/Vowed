/**
 * What a transaction costs the signer in SOL, so the app can say "you need a little devnet SOL" BEFORE it opens the wallet.
 * Account sizes were measured on devnet on 2026-10-10 from real accounts of the Vowed program (backend/scripts/rent-numbers.ts); rent comes from the
 * cluster at run time. A system account (the wallet) that keeps a balance must keep at least the rent-exempt minimum for an empty account, or the
 * transaction fails with InsufficientFundsForRent, so that floor is part of what is needed.
 */
import type { Chain } from "./types.js";

export const FEE_PER_SIGNATURE_LAMPORTS = 5_000n;

/** Bytes of each account a Vowed transaction can create. */
export const ACCOUNT_SPACE = { participation: 125, pool: 260, tokenAccount: 165, systemAccount: 0 } as const;

export type CostKind = "create" | "join" | "claim" | "freeze";

export interface SolCost {
  /** network fee */
  fee: bigint;
  /** rent for the accounts the transaction creates */
  rent: bigint;
  /** what the wallet must still hold afterwards (rent-exempt minimum of an empty account) */
  keep: bigint;
  /** fee + rent + keep: the balance the wallet needs before the transaction */
  total: bigint;
}

export interface CostOptions {
  /** the wallet has no token account for the stake token yet (a claim or freeze payment creates it) */
  userTokenAccountMissing?: boolean;
  /** a create is followed at once by the creator own join */
  thenJoin?: boolean;
}

/** SOL needed for one kind of transaction. The pool vault token account is created together with the pool. */
export async function solCost(chain: Chain, kind: CostKind, opts: CostOptions = {}): Promise<SolCost> {
  const rent = async (space: number) => BigInt(await chain.minimumBalanceForRentExemption(space));
  let fee = FEE_PER_SIGNATURE_LAMPORTS;
  let accounts = 0n;
  if (kind === "create") {
    accounts += (await rent(ACCOUNT_SPACE.pool)) + (await rent(ACCOUNT_SPACE.tokenAccount));
    if (opts.thenJoin) {
      fee += FEE_PER_SIGNATURE_LAMPORTS;
      accounts += await rent(ACCOUNT_SPACE.participation);
    }
  } else if (kind === "join") {
    accounts += await rent(ACCOUNT_SPACE.participation);
  } else if ((kind === "claim" || kind === "freeze") && opts.userTokenAccountMissing) {
    accounts += await rent(ACCOUNT_SPACE.tokenAccount);
  }
  const keep = await rent(ACCOUNT_SPACE.systemAccount);
  return { fee, rent: accounts, keep, total: fee + accounts + keep };
}
