/**
 * In-process Solana for tests: runs the real vowed.so in LiteSVM and implements the backend's Chain interface,
 * with a controllable clock. LiteSVM's Node package ships Linux/macOS binaries only, so the tests that use this
 * run in WSL (scripts/backend-test.sh) and are skipped elsewhere.
 */
import { Keypair, PublicKey, Transaction } from "@solana/web3.js";
import bs58 from "bs58";
import { getTransactionDecoder, type Address } from "@solana/kit";
import {
  ChainError,
  parseAnchorError,
  type AccountData,
  type Chain,
  type SendResult,
  type SignatureInfo,
} from "../../src/chain/types.js";
import { VowedProgram, BPF_LOADER_UPGRADEABLE } from "../../src/program/client.js";

export const SO_PATH = process.env.VOWED_SO ?? new URL("../../../programs/vowed/target/deploy/vowed.so", import.meta.url).pathname;

let litesvm: typeof import("litesvm") | null | undefined;
export async function loadLiteSvm() {
  if (litesvm === undefined) {
    try {
      litesvm = await import("litesvm");
    } catch {
      litesvm = null;
    }
  }
  return litesvm;
}

interface SentTx {
  signature: string;
  slot: number;
  failed: boolean;
  logs: string[];
  keys: Set<string>;
}

export class LiteSvmChain implements Chain {
  /** Make the next N sendAndConfirm calls fail like an RPC outage (tests the oracle retry path). */
  failNext = 0;
  /** Artificial latency per call so concurrent requests really interleave, like over real RPC. */
  delayMs = 0;
  private slotCounter = 1;
  private sent: SentTx[] = [];
  readonly svm: import("litesvm").LiteSVM;
  private readonly lib: typeof import("litesvm");

  constructor(lib: typeof import("litesvm"), program: VowedProgram, soPath = SO_PATH, upgradeAuthority?: PublicKey) {
    this.lib = lib;
    this.svm = new lib.LiteSVM();
    this.svm.addProgramFromFile(program.programId.toBase58() as Address, soPath);
    if (upgradeAuthority) this.setUpgradeAuthority(program, upgradeAuthority);
  }

  /** LiteSVM deploys with no upgrade authority; give the program one so init_config can be exercised. */
  private setUpgradeAuthority(program: VowedProgram, authority: PublicKey) {
    const pdAddr = program.programDataPda().toBase58() as Address;
    const acc = this.svm.getAccount(pdAddr);
    if (!acc.exists) throw new Error("programdata missing");
    const data = Uint8Array.from(acc.data);
    data[12] = 1;
    data.set(authority.toBytes(), 13);
    this.svm.setAccount({ ...acc, data });
  }

  airdrop(address: PublicKey | string, lamports: bigint) {
    this.svm.airdrop(new PublicKey(address).toBase58() as Address, lamports as never);
  }

  setTime(unix: number) {
    const c = this.svm.getClock();
    c.unixTimestamp = BigInt(unix);
    c.slot = c.slot + 1n;
    this.svm.setClock(c);
  }

  nowSec(): number {
    return Number(this.svm.getClock().unixTimestamp);
  }

  private async lag() {
    if (this.delayMs > 0) await new Promise((r) => setTimeout(r, this.delayMs));
  }

  async getAccount(address: string): Promise<AccountData | null> {
    await this.lag();
    const a = this.svm.getAccount(address as Address);
    if (!a.exists) return null;
    return { data: a.data, lamports: BigInt(a.lamports), owner: a.programAddress as string };
  }

  async latestBlockhash() {
    return { blockhash: this.svm.latestBlockhash() as string, lastValidBlockHeight: 1_000_000_000 };
  }

  async minimumBalanceForRentExemption(space: number): Promise<number> {
    return Number(this.svm.minimumBalanceForRentExemption(BigInt(space)));
  }

  async sendAndConfirm(tx: Transaction): Promise<SendResult> {
    await this.lag();
    if (this.failNext > 0) {
      this.failNext--;
      throw new Error("rpc unavailable");
    }
    const kitTx = getTransactionDecoder().decode(tx.serialize());
    const res = this.svm.sendTransaction(kitTx);
    this.svm.expireBlockhash();
    const failed = res instanceof this.lib.FailedTransactionMetadata;
    const meta = failed ? (res as import("litesvm").FailedTransactionMetadata).meta() : (res as import("litesvm").TransactionMetadata);
    const logs = meta.logs();
    const signature = bs58.encode(meta.signature());
    this.sent.push({
      signature,
      slot: this.slotCounter++,
      failed,
      logs,
      keys: new Set(tx.compileMessage().accountKeys.map((k) => k.toBase58())),
    });
    if (failed) {
      throw new ChainError(`transaction failed: ${String((res as import("litesvm").FailedTransactionMetadata).err())}`, logs, parseAnchorError(logs));
    }
    return { signature, logs };
  }

  async getSignaturesForAddress(address: string, opts?: { until?: string; limit?: number }): Promise<SignatureInfo[]> {
    const out: SignatureInfo[] = [];
    for (const t of [...this.sent].reverse()) {
      if (opts?.until && t.signature === opts.until) break;
      if (t.keys.has(address)) out.push({ signature: t.signature, slot: t.slot, failed: t.failed });
      if (out.length >= (opts?.limit ?? 100)) break;
    }
    return out;
  }

  async getTransactionLogs(signature: string): Promise<string[] | null> {
    return this.sent.find((t) => t.signature === signature)?.logs ?? null;
  }
}

export { Keypair, BPF_LOADER_UPGRADEABLE };
