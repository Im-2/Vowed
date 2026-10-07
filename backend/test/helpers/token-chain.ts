import { PublicKey, type Transaction } from "@solana/web3.js";
import { ATA_PROGRAM_ID, TOKEN_PROGRAM_ID } from "../../src/program/client.js";
import { ataAddress } from "../../src/util/token.js";
import type { SendResult } from "../../src/chain/types.js";
import { StubChain } from "./world.js";

/**
 * A chain that understands exactly the token instructions the faucet uses (create ATA idempotently, MintToChecked) and keeps real
 * balances, so tests check the amounts and recipients that were actually requested. The real SPL program is exercised by the WSL test.
 */
export class TokenChain extends StubChain {
  /** make the next N sends fail like an RPC outage */
  failNext = 0;
  sends = 0;
  /** artificial latency per call so simultaneous requests really interleave, like over real RPC */
  delayMs = 0;
  /** mint -> authority that is allowed to mint it */
  mintAuthority = new Map<string, string>();
  private count = 0;

  fund(address: string, lamports: bigint) {
    this.accounts.set(address, { data: new Uint8Array(0), lamports, owner: "11111111111111111111111111111111" });
  }

  balance(owner: string, mint: string): bigint {
    const a = this.accounts.get(ataAddress(owner, mint).toBase58());
    return a ? Buffer.from(a.data.subarray(64, 72)).readBigUInt64LE() : 0n;
  }

  async getAccount(a: string) {
    if (this.delayMs) await new Promise((r) => setTimeout(r, this.delayMs));
    return super.getAccount(a);
  }

  async sendAndConfirm(tx: Transaction): Promise<SendResult> {
    this.sends++;
    if (this.delayMs) await new Promise((r) => setTimeout(r, this.delayMs));
    if (this.failNext > 0) {
      this.failNext--;
      throw new Error("rpc unavailable");
    }
    const signers = new Set(tx.signatures.filter((s) => s.signature).map((s) => s.publicKey.toBase58()));
    // work on a copy so a failing instruction leaves nothing behind (transactions are atomic)
    const snapshot = new Map(this.accounts);
    try {
      for (const ix of tx.instructions) {
        if (ix.programId.equals(ATA_PROGRAM_ID)) {
          const owner = ix.keys[2]!.pubkey;
          const mint = ix.keys[3]!.pubkey;
          const ata = ataAddress(owner, mint).toBase58();
          if (!this.accounts.has(ata)) {
            const data = new Uint8Array(165);
            data.set(mint.toBytes(), 0);
            data.set(owner.toBytes(), 32);
            this.accounts.set(ata, { data, lamports: 2_039_280n, owner: TOKEN_PROGRAM_ID.toBase58() });
          }
        } else if (ix.programId.equals(TOKEN_PROGRAM_ID) && ix.data[0] === 14) {
          const mint = ix.keys[0]!.pubkey.toBase58();
          const dest = ix.keys[1]!.pubkey.toBase58();
          const authority = ix.keys[2]!.pubkey.toBase58();
          if (this.mintAuthority.get(mint) !== authority || !signers.has(authority)) throw new Error("not the mint authority");
          const acc = this.accounts.get(dest);
          if (!acc) throw new Error("destination token account does not exist");
          const amount = Buffer.from(ix.data.subarray(1, 9)).readBigUInt64LE();
          const next = Buffer.from(acc.data.subarray(64, 72)).readBigUInt64LE() + amount;
          const data = Uint8Array.from(acc.data);
          Buffer.from(data.buffer).writeBigUInt64LE(next, 64);
          this.accounts.set(dest, { ...acc, data });
        } else {
          throw new Error(`unexpected instruction for program ${ix.programId.toBase58()}`);
        }
      }
    } catch (e) {
      this.accounts = snapshot;
      throw e;
    }
    return { signature: `FakeSig${++this.count}`, logs: [] };
  }
}

export const newMint = () => PublicKey.unique().toBase58();
