import type { Transaction } from "@solana/web3.js";

export interface AccountData {
  data: Uint8Array;
  lamports: bigint;
  owner: string;
}

export interface SendResult {
  signature: string;
  logs: string[];
}

export interface SignatureInfo {
  signature: string;
  slot: number;
  failed: boolean;
}

/** A failed transaction. `anchorError` is filled when the program logged an Anchor error. */
export class ChainError extends Error {
  constructor(
    message: string,
    readonly logs: string[],
    readonly anchorError?: { code: string; number: number },
  ) {
    super(message);
    this.name = "ChainError";
  }
}

export function parseAnchorError(logs: readonly string[]): { code: string; number: number } | undefined {
  for (const l of logs) {
    const m = /Error Code: (\w+)\. Error Number: (\d+)/.exec(l);
    if (m) return { code: m[1]!, number: Number(m[2]) };
  }
  return undefined;
}

/** One token transfer found in a confirmed transaction. */
export interface TokenTransfer {
  mint: string;
  source: string;
  destination: string;
  /** the wallet that signed the transfer */
  authority: string;
  amount: bigint;
}

/** The result of running a transaction on the cluster without sending it. */
export interface SimulationResult {
  /** null when the transaction would succeed; otherwise the JSON of the cluster's error */
  err: string | null;
  logs: string[];
  unitsConsumed?: number;
}

/** Everything the backend needs from Solana. Implemented over RPC for devnet and over LiteSVM in tests. */
export interface Chain {
  getAccount(address: string): Promise<AccountData | null>;
  latestBlockhash(): Promise<{ blockhash: string; lastValidBlockHeight: number }>;
  /** Sends a fully signed transaction and waits for confirmation. Throws ChainError on failure. */
  sendAndConfirm(tx: Transaction): Promise<SendResult>;
  /** Cluster time in unix seconds. The program's own clock is authoritative; this is only used for UX and pre-checks. */
  nowSec(): number;
  minimumBalanceForRentExemption(space: number): Promise<number>;
  /** Newest first. `until` stops at an already processed signature. */
  getSignaturesForAddress(address: string, opts?: { until?: string; before?: string; limit?: number }): Promise<SignatureInfo[]>;
  getTransactionLogs(signature: string): Promise<string[] | null>;
  /** The token transfers of a CONFIRMED, SUCCESSFUL transaction, or null when it is unknown or failed. */
  getTokenTransfers(signature: string): Promise<TokenTransfer[] | null>;
  /** Runs the transaction on the cluster WITHOUT sending it (no signatures needed). Optional: test chains that cannot do it leave it out. */
  simulate?(tx: Transaction): Promise<SimulationResult>;
}
