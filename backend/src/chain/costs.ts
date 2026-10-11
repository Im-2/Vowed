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
  /** the create also contains the creator own join (one transaction, one signature) */
  thenJoin?: boolean;
}

/** SOL needed for one kind of transaction. The pool vault token account is created together with the pool. */
export async function solCost(chain: Chain, kind: CostKind, opts: CostOptions = {}): Promise<SolCost> {
  const rent = async (space: number) => BigInt(await chain.minimumBalanceForRentExemption(space));
  const fee = FEE_PER_SIGNATURE_LAMPORTS;
  let accounts = 0n;
  if (kind === "create") {
    accounts += (await rent(ACCOUNT_SPACE.pool)) + (await rent(ACCOUNT_SPACE.tokenAccount));
    if (opts.thenJoin) {
      // create and join are ONE transaction with one signature: one fee, plus the rent of the participation record
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

/** What a transaction needs in SOL and what the wallet holds, for the review screen and for the server's own check. All amounts are lamports as text. */
export interface SolQuote {
  fee: string;
  rent: string;
  keep: string;
  total: string;
  /** the wallet's balance when the transaction was built */
  balance: string;
  enough: boolean;
}

export async function solQuote(chain: Chain, wallet: string, kind: CostKind, opts: CostOptions = {}): Promise<SolQuote> {
  const cost = await solCost(chain, kind, opts);
  const acc = await chain.getAccount(wallet);
  const balance = acc?.lamports ?? 0n;
  return { fee: cost.fee.toString(), rent: cost.rent.toString(), keep: cost.keep.toString(), total: cost.total.toString(), balance: balance.toString(), enough: balance >= cost.total };
}

/** lamports as "0.0054" (SOL, at most 4 decimals, rounded up so a short wallet never reads as enough) */
export function solText(lamports: string | bigint): string {
  const v = BigInt(lamports);
  const tenThousandths = (v + 99_999n) / 100_000n; // round up to 0.0001 SOL
  const whole = tenThousandths / 10_000n;
  const frac = (tenThousandths % 10_000n).toString().padStart(4, "0").replace(/0+$/, "");
  return frac ? `${whole}.${frac}` : `${whole}`;
}
