import { Connection, PublicKey, SendTransactionError, type Transaction } from "@solana/web3.js";
import {
  ChainError,
  parseAnchorError,
  type AccountData,
  type Chain,
  type SendResult,
  type SignatureInfo,
} from "./types.js";

/** Chain over a JSON-RPC endpoint (devnet / local validator). */
export class Web3Chain implements Chain {
  readonly connection: Connection;
  constructor(rpcUrl: string) {
    this.connection = new Connection(rpcUrl, "confirmed");
  }

  async getAccount(address: string): Promise<AccountData | null> {
    const info = await this.connection.getAccountInfo(new PublicKey(address), "confirmed");
    if (!info) return null;
    return { data: info.data, lamports: BigInt(info.lamports), owner: info.owner.toBase58() };
  }

  async latestBlockhash() {
    return this.connection.getLatestBlockhash("confirmed");
  }

  async sendAndConfirm(tx: Transaction): Promise<SendResult> {
    const raw = tx.serialize();
    let signature: string;
    try {
      signature = await this.connection.sendRawTransaction(raw, { skipPreflight: false, preflightCommitment: "confirmed" });
    } catch (e) {
      if (e instanceof SendTransactionError) {
        const logs = e.logs ?? (await e.getLogs(this.connection).catch(() => [])) ?? [];
        throw new ChainError(`transaction rejected: ${e.message}`, logs, parseAnchorError(logs));
      }
      throw e;
    }
    const bh = tx.recentBlockhash!;
    const lastValidBlockHeight = tx.lastValidBlockHeight ?? (await this.connection.getLatestBlockhash("confirmed")).lastValidBlockHeight;
    const res = await this.connection.confirmTransaction({ signature, blockhash: bh, lastValidBlockHeight }, "confirmed");
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
    return this.connection.getMinimumBalanceForRentExemption(space);
  }

  async getSignaturesForAddress(address: string, opts?: { until?: string; limit?: number }): Promise<SignatureInfo[]> {
    const sigs = await this.connection.getSignaturesForAddress(
      new PublicKey(address),
      { until: opts?.until, limit: opts?.limit ?? 100 },
      "confirmed",
    );
    return sigs.map((s) => ({ signature: s.signature, slot: s.slot, failed: s.err !== null }));
  }

  async getTransactionLogs(signature: string): Promise<string[] | null> {
    for (let i = 0; i < 5; i++) {
      const tx = await this.connection.getTransaction(signature, { commitment: "confirmed", maxSupportedTransactionVersion: 0 });
      if (tx?.meta?.logMessages) return tx.meta.logMessages;
      await new Promise((r) => setTimeout(r, 400));
    }
    return null;
  }
}
