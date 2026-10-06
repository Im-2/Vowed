/**
 * Completes an interrupted program-upload buffer on devnet, politely.
 *
 *   npx tsx scripts/fill-buffer.ts <BUFFER_ADDRESS> <path/to/vowed.so>
 *
 * Reads the buffer, finds the 900-byte chunks that differ from the local binary, writes only those (the buffer authority must be the
 * deployer key in backend/.devnet/deployer.json), paced for the rate-limited public RPC, and repeats until the buffer equals the binary.
 * Then run `solana program deploy <so> --program-id <kp> --buffer <BUFFER>` (or `solana program upgrade`) to finish. Never calls a faucet.
 */
import { readFileSync } from "node:fs";
import { Connection, Keypair, PublicKey, Transaction, TransactionInstruction } from "@solana/web3.js";

const [bufferArg, soPath] = process.argv.slice(2);
if (!bufferArg || !soPath) throw new Error("usage: fill-buffer.ts <BUFFER_ADDRESS> <vowed.so>");
const RPC = process.env.RPC_URL ?? "https://api.devnet.solana.com";
const LOADER = new PublicKey("BPFLoaderUpgradeab1e11111111111111111111111");
const HEADER = 37; // UpgradeableLoaderState::Buffer { authority_address: Option<Pubkey> }
const CHUNK = 900;
const PER_SECOND = Number(process.env.TX_PER_SECOND ?? 5);

const payer = Keypair.fromSecretKey(Uint8Array.from(JSON.parse(readFileSync(new URL("../.devnet/deployer.json", import.meta.url), "utf8")) as number[]));
const buffer = new PublicKey(bufferArg);
const want = new Uint8Array(readFileSync(soPath));
const conn = new Connection(RPC, { commitment: "confirmed", disableRetryOnRateLimit: false });
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

async function retry<T>(label: string, fn: () => Promise<T>): Promise<T> {
  for (let i = 0; ; i++) {
    try {
      return await fn();
    } catch (e) {
      if (i >= 8) throw e;
      const wait = Math.min(15_000, 700 * 2 ** i);
      console.log(`  ${label}: ${(e as Error).message.slice(0, 80)} (retry in ${wait} ms)`);
      await sleep(wait);
    }
  }
}

function writeIx(offset: number, bytes: Uint8Array): TransactionInstruction {
  const data = Buffer.alloc(4 + 4 + 8 + bytes.length);
  data.writeUInt32LE(1, 0); // UpgradeableLoaderInstruction::Write
  data.writeUInt32LE(offset, 4);
  data.writeBigUInt64LE(BigInt(bytes.length), 8);
  Buffer.from(bytes).copy(data, 16);
  return new TransactionInstruction({
    programId: LOADER,
    keys: [
      { pubkey: buffer, isSigner: false, isWritable: true },
      { pubkey: payer.publicKey, isSigner: true, isWritable: false },
    ],
    data,
  });
}

async function missingChunks(): Promise<number[]> {
  const info = await retry("read buffer", () => conn.getAccountInfo(buffer, "confirmed"));
  if (!info) throw new Error("buffer account does not exist");
  if (info.data.length - HEADER < want.length) throw new Error(`buffer too small: ${info.data.length - HEADER} < ${want.length}`);
  const have = info.data.subarray(HEADER, HEADER + want.length);
  const out: number[] = [];
  for (let off = 0; off < want.length; off += CHUNK) {
    const end = Math.min(off + CHUNK, want.length);
    if (Buffer.compare(Buffer.from(have.subarray(off, end)), Buffer.from(want.subarray(off, end))) !== 0) out.push(off);
  }
  return out;
}

async function main() {
  console.log(`buffer ${buffer.toBase58()}, binary ${want.length} bytes, ${Math.ceil(want.length / CHUNK)} chunks of ${CHUNK}, ${PER_SECOND} tx/s`);
  for (let pass = 1; pass <= 12; pass++) {
    const todo = await missingChunks();
    console.log(`pass ${pass}: ${todo.length} chunks missing`);
    if (todo.length === 0) {
      console.log("BUFFER COMPLETE: byte-identical to the local binary");
      return;
    }
    const BATCH = 10;
    for (let i = 0; i < todo.length; i += BATCH) {
      const batch = todo.slice(i, i + BATCH);
      const { blockhash, lastValidBlockHeight } = await retry("blockhash", () => conn.getLatestBlockhash("confirmed"));
      const sigs: string[] = [];
      for (const off of batch) {
        const tx = new Transaction({ feePayer: payer.publicKey, blockhash, lastValidBlockHeight }).add(writeIx(off, want.subarray(off, Math.min(off + CHUNK, want.length))));
        tx.sign(payer);
        sigs.push(await retry("send", () => conn.sendRawTransaction(tx.serialize(), { skipPreflight: true, maxRetries: 3 })));
        await sleep(1000 / PER_SECOND);
      }
      await sleep(1500); // let them land; the next pass re-reads the buffer, so lost transactions are simply sent again
      if ((i / BATCH) % 5 === 0) console.log(`  sent ${Math.min(i + BATCH, todo.length)}/${todo.length}`);
    }
    await sleep(3000);
  }
  throw new Error("buffer still incomplete after 12 passes");
}

main().then(
  () => process.exit(0),
  (e) => {
    console.error(e instanceof Error ? e.message : e);
    process.exit(1);
  },
);
