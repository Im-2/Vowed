import type { Transaction } from "@solana/web3.js";
import { ApiError } from "../errors.js";
import { solQuote, solText, type CostKind, type CostOptions, type SolQuote } from "./costs.js";
import { parseAnchorError, type Chain } from "./types.js";

/**
 * Running a transaction on the cluster BEFORE the wallet sees it. A wallet only shows an approval prompt for a transaction it can make sense of,
 * and a transaction that would fail wastes the person's time (and, on some wallets, shows nothing at all). So the backend simulates each
 * transaction it builds and, if it would fail, answers with a plain reason instead of handing it out.
 */
export interface SimulationSummary {
  ok: boolean;
  /** true when this chain cannot simulate (tests with an in-memory chain); nothing was checked */
  skipped: boolean;
  unitsConsumed?: number;
}

export interface SimulationFailure {
  code: "insufficient_sol" | "insufficient_tokens" | "blockhash_expired" | "program_rejected" | "simulation_failed";
  message: string;
}

const SOL_WORDS = "Your wallet needs more devnet SOL for network fees and account rent. Tap Get test SOL.";

/** Turns the cluster's error and logs into one plain sentence and a stable code. */
export function explainSimulation(err: string, logs: readonly string[]): SimulationFailure {
  const text = `${err} ${logs.join(" ")}`;
  const lower = text.toLowerCase();
  const anchor = parseAnchorError(logs);
  if (anchor) return { code: "program_rejected", message: `The Vowed program refused this step (${anchor.code}). Check the challenge is still open and try again.` };
  if (lower.includes("insufficientfundsforrent") || lower.includes("accountnotfound") || lower.includes("insufficient lamports") || lower.includes("prior credit")) {
    return { code: "insufficient_sol", message: SOL_WORDS };
  }
  if (lower.includes("insufficientfundsforfee")) return { code: "insufficient_sol", message: SOL_WORDS };
  if (lower.includes("blockhashnotfound")) return { code: "blockhash_expired", message: "This request expired before it could be checked. Go back and try again." };
  if (lower.includes("insufficient funds") || /"custom":1/.test(err)) return { code: "insufficient_tokens", message: "You do not have enough tokens for this. Tap Get test tokens to receive some." };
  return { code: "simulation_failed", message: "The network refused this step in a test run, so your wallet was not opened. Try again in a moment." };
}

/** Simulates [tx]; throws a 422 with a plain reason when it would fail. Returns what was checked. */
export async function simulateOrThrow(chain: Chain, tx: Transaction): Promise<SimulationSummary> {
  if (!chain.simulate) return { ok: true, skipped: true };
  const sim = await chain.simulate(tx);
  if (sim.err) {
    const f = explainSimulation(sim.err, sim.logs);
    throw new ApiError(422, f.code, f.message, { error: sim.err.slice(0, 300), logs: sim.logs.slice(-6) });
  }
  return { ok: true, skipped: false, unitsConsumed: sim.unitsConsumed };
}

/**
 * The server's own SOL check, made BEFORE a transaction is built or simulated and independent of the app's pre-flight call: a wallet that cannot pay the
 * fee and the rent of the accounts the transaction creates is refused with the numbers, so it is never sent to a wallet app for a transaction that must fail.
 * Returns the quote (also sent to the app for the review screen).
 */
export async function requireSol(chain: Chain, wallet: string, kind: CostKind, opts: CostOptions = {}): Promise<SolQuote> {
  const q = await solQuote(chain, wallet, kind, opts);
  if (!q.enough) {
    throw new ApiError(
      422,
      "insufficient_sol",
      `Your wallet needs about ${solText(q.total)} SOL on devnet for the network fee and account rent. You have ${solText(q.balance)} SOL. Tap Get test SOL.`,
      { needed: q.total, balance: q.balance, fee: q.fee, rent: q.rent, keep: q.keep },
    );
  }
  return q;
}
