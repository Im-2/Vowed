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
  getSignaturesForAddress(address: string, opts?: { until?: string; limit?: number }): Promise<SignatureInfo[]>;
  getTransactionLogs(signature: string): Promise<string[] | null>;
}
