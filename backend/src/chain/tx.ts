import { Transaction, type Keypair, type PublicKey, type TransactionInstruction } from "@solana/web3.js";
import type { Chain, SendResult } from "./types.js";

/** An unsigned legacy transaction with the fee payer and a recent blockhash set. */
export async function buildTransaction(
  chain: Chain,
  feePayer: PublicKey,
  instructions: TransactionInstruction[],
): Promise<Transaction> {
  const { blockhash, lastValidBlockHeight } = await chain.latestBlockhash();
  const tx = new Transaction({ feePayer, blockhash, lastValidBlockHeight });
  tx.add(...instructions);
  return tx;
}

/** Signs with the given keypairs (first one pays) and sends. */
export async function signAndSend(chain: Chain, instructions: TransactionInstruction[], signers: Keypair[]): Promise<SendResult> {
  const tx = await buildTransaction(chain, signers[0]!.publicKey, instructions);
  tx.sign(...signers);
  return chain.sendAndConfirm(tx);
}
