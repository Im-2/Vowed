import { Connection, PublicKey, SendTransactionError, type Transaction } from "@solana/web3.js";
import {
  ChainError,
  parseAnchorError,
  type AccountData,
  type Chain,
  type SendResult,
  type SignatureInfo,
} from "./types.js";

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

/**
 * Runs an RPC read. Transient failures (rate limits, dropped connections) are retried a few times with a short backoff;
 * what still fails is reported as a ChainError (HTTP 502) rather than an internal error.
 */
async function rpc<T>(what: string, fn: () => Promise<T>): Promise<T> {
  let last: unknown;
  for (let i = 0; i < 4; i++) {
    try {
      return await fn();
    } catch (e) {
      if (e instanceof ChainError) throw e;
      last = e;
      await sleep(400 * 2 ** i);
    }
  }
  throw new ChainError(`${what} failed: ${last instanceof Error ? last.message : String(last)}`, [], undefined);
}

/** Chain over a JSON-RPC endpoint (devnet / local validator). */
export class Web3Chain implements Chain {
  readonly connection: Connection;
  constructor(rpcUrl: string) {
    this.connection = new Connection(rpcUrl, "confirmed");
  }

  async getAccount(address: string): Promise<AccountData | null> {
    const info = await rpc("getAccountInfo", () => this.connection.getAccountInfo(new PublicKey(address), "confirmed"));
    if (!info) return null;
    return { data: info.data, lamports: BigInt(info.lamports), owner: info.owner.toBase58() };
  }

  async latestBlockhash() {
    return rpc("getLatestBlockhash", () => this.connection.getLatestBlockhash("confirmed"));
  }

  async sendAndConfirm(tx: Transaction): Promise<SendResult> {
    const raw = tx.serialize();
    let signature = "";
    let lastNetworkError: unknown;
    // Sending the same signed bytes again is harmless (same signature), so a dropped connection can be retried.
    for (let attempt = 0; attempt < 4; attempt++) {
      try {
        signature = await this.connection.sendRawTransaction(raw, { skipPreflight: false, preflightCommitment: "confirmed" });
        break;
      } catch (e) {
        if (e instanceof SendTransactionError) {
          const logs = e.logs ?? (await e.getLogs(this.connection).catch(() => [])) ?? [];
          throw new ChainError(`transaction rejected: ${e.message}`, logs, parseAnchorError(logs));
        }
        lastNetworkError = e;
        await sleep(500 * 2 ** attempt);
      }
    }
    if (!signature) throw new ChainError(`sending the transaction failed: ${lastNetworkError instanceof Error ? lastNetworkError.message : String(lastNetworkError)}`, [], undefined);
    const bh = tx.recentBlockhash!;
    const lastValidBlockHeight = tx.lastValidBlockHeight ?? (await this.connection.getLatestBlockhash("confirmed")).lastValidBlockHeight;
    const res = await rpc("confirmTransaction", () => this.connection.confirmTransaction({ signature, blockhash: bh, lastValidBlockHeight }, "confirmed"));
    const logs = (await this.getTransactionLogs(signature)) ?? [];
    if (res.value.err) {
      throw new ChainError(`transaction failed: ${JSON.stringify(res.value.err)}`, logs, parseAnchorError(logs));
    }
    return { signature, logs };
  }

  nowSec(): number {
    return Math.floor(Date.now() / 1000);
  }

  async minimumBalanceForRentExemption(space: number): Promise<number> {
    return rpc("getMinimumBalanceForRentExemption", () => this.connection.getMinimumBalanceForRentExemption(space));
  }

  async getSignaturesForAddress(address: string, opts?: { until?: string; before?: string; limit?: number }): Promise<SignatureInfo[]> {
    const sigs = await rpc("getSignaturesForAddress", () =>
      this.connection.getSignaturesForAddress(new PublicKey(address), { until: opts?.until, before: opts?.before, limit: opts?.limit ?? 100 }, "confirmed"),
    );
    return sigs.map((s) => ({ signature: s.signature, slot: s.slot, failed: s.err !== null }));
  }

  async getTransactionLogs(signature: string): Promise<string[] | null> {
    for (let i = 0; i < 5; i++) {
      const tx = await rpc("getTransaction", () => this.connection.getTransaction(signature, { commitment: "confirmed", maxSupportedTransactionVersion: 0 }));
      if (tx?.meta?.logMessages) return tx.meta.logMessages;
      await new Promise((r) => setTimeout(r, 400));
    }
    return null;
  }
}
