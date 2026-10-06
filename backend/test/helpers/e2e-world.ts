import { Keypair, Transaction } from "@solana/web3.js";
import { createHash } from "node:crypto";
import nacl from "tweetnacl";
import { signAndSend } from "../../src/chain/tx.js";
import { deviceRegistrationMessage } from "../../src/http/devices.js";
import { ataAddress, ixCreateAtaIdempotent, ixMintTo, ixsCreateMint, tokenAmount } from "../../src/util/token.js";
import { LiteSvmChain, loadLiteSvm } from "./litesvm-chain.js";
import { fakeAttestation, makeDevice, makeWorld, signIn, type TestDevice, type World } from "./world.js";
import { VowedProgram } from "../../src/program/client.js";

export const DAY = 86_400;
export const BASE = 1_799_971_200; // UTC midnight
export const UNIT = 1_000_000n;

export interface ChainWorld extends World {
  chain: LiteSvmChain;
  admin: Keypair;
  treasury: Keypair;
  mint: Keypair;
  mintB: Keypair;
  program: VowedProgram;
}

export interface Player {
  kp: Keypair;
  wallet: string;
  headers: { authorization: string };
  device: TestDevice;
  token: string;
}

export async function makeChainWorld(opts: { settleGraceSecs?: bigint; maxStake?: bigint; feeBps?: number; demoEnabled?: boolean; demoMaxStake?: bigint } = {}): Promise<ChainWorld | null> {
  const lib = await loadLiteSvm();
  if (!lib) return null;
  const program = new VowedProgram();
  const admin = Keypair.generate();
  const treasury = Keypair.generate();
  const mint = Keypair.generate();
  const mintB = Keypair.generate(); // allowed for normal pools but NOT for demo pools
  const chain = new LiteSvmChain(lib, program, undefined, admin.publicKey);
  const w = await makeWorld({ chain, attestation: fakeAttestation() });
  for (const k of [admin, w.oracle, w.crank, treasury]) chain.airdrop(k.publicKey, 20_000_000_000n);
  chain.setTime(BASE);
  const rent = await chain.minimumBalanceForRentExemption(82);
  await signAndSend(chain, ixsCreateMint(admin.publicKey, mint.publicKey, admin.publicKey, 6, rent), [admin, mint]);
  await signAndSend(chain, ixsCreateMint(admin.publicKey, mintB.publicKey, admin.publicKey, 6, rent), [admin, mintB]);
  await signAndSend(
    chain,
    [
      program.ixInitConfig(admin.publicKey, {
        oracle: w.oracle.publicKey.toBase58(),
        treasury: treasury.publicKey.toBase58(),
        fee_bps: opts.feeBps ?? 0,
        max_stake: opts.maxStake ?? 100n * UNIT,
        settle_grace_secs: opts.settleGraceSecs ?? 7_200n,
        allowed_mints: [mint.publicKey.toBase58(), mintB.publicKey.toBase58()],
        demo_enabled: opts.demoEnabled ?? true,
        demo_max_stake: opts.demoMaxStake ?? 5n * UNIT,
        demo_mints: [mint.publicKey.toBase58()],
      }),
    ],
    [admin],
  );
  return { ...w, chain, admin, treasury, mint, mintB, program };
}

/** A funded wallet with a token balance, a registered (attested) device, and a signed-in session. */
export async function makePlayer(w: ChainWorld, tokens: bigint = 100n * UNIT): Promise<Player> {
  const kp = Keypair.generate();
  w.chain.airdrop(kp.publicKey, 5_000_000_000n);
  await signAndSend(w.chain, [ixCreateAtaIdempotent(w.admin.publicKey, kp.publicKey, w.mint.publicKey)], [w.admin]);
  const token = ataAddress(kp.publicKey, w.mint.publicKey).toBase58();
  if (tokens > 0n) {
    await signAndSend(w.chain, [ixMintTo(w.mint.publicKey, ataAddress(kp.publicKey, w.mint.publicKey), w.admin.publicKey, tokens, 6)], [w.admin]);
  }
  const auth = await signIn(w, kp);
  const device = makeDevice();
  const { challenge } = (await w.app.inject({ method: "POST", url: "/v1/devices/challenge", headers: auth.headers })).json();
  const msg = new TextEncoder().encode(deviceRegistrationMessage(kp.publicKey.toBase58(), device.id, challenge));
  const reg = await w.app.inject({
    method: "POST",
    url: "/v1/devices/register",
    headers: auth.headers,
    payload: { devicePublicKey: device.spki.toString("base64"), attestationChain: ["Zm9v", "YmFy"], challenge, walletSignature: Buffer.from(nacl.sign.detached(msg, kp.secretKey)).toString("base64") },
  });
  if (reg.statusCode !== 200) throw new Error(`device registration failed: ${reg.body}`);
  return { kp, wallet: kp.publicKey.toBase58(), headers: auth.headers, device, token };
}

export async function balance(w: ChainWorld, token: string): Promise<bigint> {
  const acc = await w.chain.getAccount(token);
  return acc ? tokenAmount(acc.data) : 0n;
}

/** Signs a base64 unsigned transaction from the API with the player's wallet and sends it, like MWA would. */
export async function signAndSendB64(w: ChainWorld, p: Player, b64: string): Promise<string> {
  const tx = Transaction.from(Buffer.from(b64, "base64"));
  tx.partialSign(p.kp);
  const res = await w.chain.sendAndConfirm(tx);
  await w.app.inject({ method: "POST", url: "/v1/challenges/sync", headers: p.headers, payload: { signature: res.signature } });
  return res.signature;
}

export async function post(w: World, p: { headers: { authorization: string } }, url: string, payload: unknown, extra: Record<string, string> = {}) {
  return w.app.inject({ method: "POST", url, headers: { ...p.headers, ...extra }, payload: payload as object });
}
export async function get(w: World, p: { headers: { authorization: string } }, url: string) {
  return w.app.inject({ method: "GET", url, headers: p.headers });
}

export const sha = (b: Uint8Array | string) => createHash("sha256").update(b).digest("hex");
