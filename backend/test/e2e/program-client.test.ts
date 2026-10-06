import { Keypair, PublicKey } from "@solana/web3.js";
import { beforeAll, describe, expect, it } from "vitest";
import { signAndSend } from "../../src/chain/tx.js";
import { ChainError } from "../../src/chain/types.js";
import { VowedProgram } from "../../src/program/client.js";
import { ataAddress, ixCreateAtaIdempotent, ixMintTo, ixsCreateMint, tokenAmount } from "../../src/util/token.js";
import { LiteSvmChain, loadLiteSvm } from "../helpers/litesvm-chain.js";

const lib = await loadLiteSvm();
const DAY = 86_400n;
const BASE = 1_799_971_200n; // UTC midnight

describe.skipIf(!lib)("program client against the real vowed.so (LiteSVM)", () => {
  const program = new VowedProgram();
  const admin = Keypair.generate();
  const oracle = Keypair.generate();
  const treasury = Keypair.generate();
  const alice = Keypair.generate();
  const bob = Keypair.generate();
  const mintKp = Keypair.generate();
  let chain: LiteSvmChain;

  beforeAll(async () => {
    chain = new LiteSvmChain(lib!, program, undefined, admin.publicKey);
    for (const k of [admin, oracle, treasury, alice, bob]) chain.airdrop(k.publicKey, 10_000_000_000n);
    chain.setTime(Number(BASE));
    const rent = await chain.minimumBalanceForRentExemption(82);
    await signAndSend(chain, ixsCreateMint(admin.publicKey, mintKp.publicKey, admin.publicKey, 6, rent), [admin, mintKp]);
    for (const [owner] of [[alice], [bob], [treasury]] as [Keypair][]) {
      await signAndSend(chain, [ixCreateAtaIdempotent(admin.publicKey, owner.publicKey, mintKp.publicKey)], [admin]);
    }
    for (const owner of [alice, bob]) {
      await signAndSend(chain, [ixMintTo(mintKp.publicKey, ataAddress(owner.publicKey, mintKp.publicKey), admin.publicKey, 100_000_000n, 6)], [admin]);
    }
    await signAndSend(
      chain,
      [
        program.ixInitConfig(admin.publicKey, {
          oracle: oracle.publicKey.toBase58(),
          treasury: treasury.publicKey.toBase58(),
          fee_bps: 0,
          max_stake: 100_000_000n,
          settle_grace_secs: 7_200n,
          allowed_mints: [mintKp.publicKey.toBase58()],
        }),
      ],
      [admin],
    );
  });

  it("decodes the config account written by the program", async () => {
    const acc = await chain.getAccount(program.configPda().toBase58());
    const cfg = program.decodeConfig(acc!.data);
    expect(cfg.admin).toBe(admin.publicKey.toBase58());
    expect(cfg.oracle).toBe(oracle.publicKey.toBase58());
    expect(cfg.max_stake).toBe(100_000_000n);
    expect(cfg.settle_grace_secs).toBe(7_200n);
    expect(cfg.allowed_mints.slice(0, cfg.allowed_mint_count)).toEqual([mintKp.publicKey.toBase58()]);
  });

  it("runs a full pool lifecycle through the client, with the clock advanced", async () => {
    const poolId = 1n;
    const startTs = BASE + DAY;
    const create = program.ixCreatePool(alice.publicKey, mintKp.publicKey, {
      pool_id: poolId,
      kind: "Squad",
      mode: "Hard",
      penalty_bps: 10_000,
      start_ts: startTs,
      duration_days: 2,
      required_days: 1,
      goal_hash: new Uint8Array(32).fill(5),
      join_window_secs: 3_600n,
      max_participants: 10,
    });
    const created = await signAndSend(chain, [create], [alice]);
    const events = program.decodeEvents(created.logs);
    expect(events.map((e) => e.name)).toEqual(["PoolCreated"]);

    const poolKey = program.poolPda(alice.publicKey, poolId);
    const poolAcc = program.decodePool((await chain.getAccount(poolKey.toBase58()))!.data);
    expect(poolAcc.status).toBe("Open");
    expect(poolAcc.mode).toBe("Hard");
    expect(poolAcc.end_ts).toBe(startTs + 2n * DAY);
    expect(Buffer.from(poolAcc.goal_hash)).toEqual(Buffer.alloc(32, 5));
    const ref = { key: poolKey, mint: mintKp.publicKey, vault: new PublicKey(poolAcc.vault) };

    for (const [user, stake] of [[alice, 30_000_000n], [bob, 10_000_000n]] as [Keypair, bigint][]) {
      const join = program.ixJoinPool(user.publicKey, ref, ataAddress(user.publicKey, mintKp.publicKey), stake, 0, new Uint8Array(32).fill(1));
      await signAndSend(chain, [join], [user]);
    }
    const vault = await chain.getAccount(ref.vault.toBase58());
    expect(tokenAmount(vault!.data)).toBe(40_000_000n);

    // alice checks in on day 0 (oracle-signed); bob does not
    chain.setTime(Number(startTs + 3_600n));
    const ci = await signAndSend(chain, [program.ixRecordCheckin(oracle.publicKey, poolKey, alice.publicKey, 0)], [oracle]);
    expect(program.decodeEvents(ci.logs)[0]!.name).toBe("CheckinRecorded");
    const part = program.decodeParticipation((await chain.getAccount(program.participationPda(poolKey, alice.publicKey).toBase58()))!.data);
    expect(part.days_completed).toBe(1);

    // a wrong signer is rejected with the program's own error
    await expect(signAndSend(chain, [program.ixRecordCheckin(alice.publicKey, poolKey, alice.publicKey, 0)], [alice])).rejects.toMatchObject({
      anchorError: { code: "Unauthorized" },
    });
    await expect(signAndSend(chain, [program.ixRecordCheckin(oracle.publicKey, poolKey, alice.publicKey, 0)], [oracle])).rejects.toBeInstanceOf(ChainError);

    // settle after end + grace, then claim
    chain.setTime(Number(poolAcc.settle_after_ts));
    for (const user of [alice, bob]) {
      await signAndSend(chain, [program.ixSettle(poolKey, user.publicKey)], [admin]);
    }
    const settled = program.decodePool((await chain.getAccount(poolKey.toBase58()))!.data);
    expect(settled.status).toBe("Settled");
    expect(settled.distributable).toBe(10_000_000n);

    const claim = await signAndSend(chain, [program.ixClaim(alice.publicKey, ref, ataAddress(alice.publicKey, mintKp.publicKey))], [alice]);
    expect(program.decodeEvents(claim.logs)[0]).toMatchObject({ name: "Claimed" });
    const aliceBal = await chain.getAccount(ataAddress(alice.publicKey, mintKp.publicKey).toBase58());
    expect(tokenAmount(aliceBal!.data)).toBe(70_000_000n + 40_000_000n); // 100 - 30 staked + 30 stake + bob's 10 forfeited
  });
});
