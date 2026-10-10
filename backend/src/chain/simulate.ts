import type { Transaction } from "@solana/web3.js";
import { ApiError } from "../errors.js";
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

const SOL_WORDS = "Your wallet needs a little devnet SOL for network fees and account rent. Tap Get test tokens to receive some.";

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
