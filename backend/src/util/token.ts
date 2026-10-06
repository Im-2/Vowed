/** Hand-written SPL Token helpers (we deliberately avoid the @solana/spl-token package: its dependency tree carries a high-severity advisory). */
import { PublicKey, SystemProgram, TransactionInstruction } from "@solana/web3.js";
import { ATA_PROGRAM_ID, SYSTEM_PROGRAM_ID, TOKEN_PROGRAM_ID } from "../program/client.js";

export const MINT_ACCOUNT_SIZE = 82;

export function ataAddress(owner: PublicKey | string, mint: PublicKey | string): PublicKey {
  return PublicKey.findProgramAddressSync(
    [new PublicKey(owner).toBuffer(), TOKEN_PROGRAM_ID.toBuffer(), new PublicKey(mint).toBuffer()],
    ATA_PROGRAM_ID,
  )[0];
}

/** CreateIdempotent (instruction 1): succeeds if the account already exists. */
export function ixCreateAtaIdempotent(payer: PublicKey, owner: PublicKey | string, mint: PublicKey | string): TransactionInstruction {
  const ata = ataAddress(owner, mint);
  return new TransactionInstruction({
    programId: ATA_PROGRAM_ID,
    keys: [
      { pubkey: payer, isSigner: true, isWritable: true },
      { pubkey: ata, isSigner: false, isWritable: true },
      { pubkey: new PublicKey(owner), isSigner: false, isWritable: false },
      { pubkey: new PublicKey(mint), isSigner: false, isWritable: false },
      { pubkey: SYSTEM_PROGRAM_ID, isSigner: false, isWritable: false },
      { pubkey: TOKEN_PROGRAM_ID, isSigner: false, isWritable: false },
    ],
    data: Buffer.from([1]),
  });
}

/** SystemProgram.createAccount + InitializeMint2 (no freeze authority). Devnet test mints only. */
export function ixsCreateMint(payer: PublicKey, mint: PublicKey, mintAuthority: PublicKey, decimals: number, rentLamports: number): TransactionInstruction[] {
  const create = SystemProgram.createAccount({
    fromPubkey: payer,
    newAccountPubkey: mint,
    lamports: rentLamports,
    space: MINT_ACCOUNT_SIZE,
    programId: TOKEN_PROGRAM_ID,
  });
  const data = Buffer.alloc(1 + 1 + 32 + 1);
  data[0] = 20; // InitializeMint2
  data[1] = decimals;
  mintAuthority.toBuffer().copy(data, 2);
  data[34] = 0; // no freeze authority
  return [
    create,
    new TransactionInstruction({ programId: TOKEN_PROGRAM_ID, keys: [{ pubkey: mint, isSigner: false, isWritable: true }], data }),
  ];
}

/** MintToChecked (instruction 14). */
export function ixMintTo(mint: PublicKey, dest: PublicKey, authority: PublicKey, amount: bigint, decimals: number): TransactionInstruction {
  const data = Buffer.alloc(10);
  data[0] = 14;
  data.writeBigUInt64LE(amount, 1);
  data[9] = decimals;
  return new TransactionInstruction({
    programId: TOKEN_PROGRAM_ID,
    keys: [
      { pubkey: mint, isSigner: false, isWritable: true },
      { pubkey: dest, isSigner: false, isWritable: true },
      { pubkey: authority, isSigner: true, isWritable: false },
    ],
    data,
  });
}

/** Amount field of an SPL token account (offset 64, u64 LE). */
export function tokenAmount(data: Uint8Array): bigint {
  if (data.length < 72) throw new Error("not a token account");
  return Buffer.from(data.subarray(64, 72)).readBigUInt64LE();
}
/** Owner (authority) field of an SPL token account. */
export function tokenOwner(data: Uint8Array): string {
  return new PublicKey(data.subarray(32, 64)).toBase58();
}
