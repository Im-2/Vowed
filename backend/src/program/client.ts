import { PublicKey, TransactionInstruction } from "@solana/web3.js";
import idlJson from "./vowed.idl.json" with { type: "json" };
import { Codec, type Idl } from "./borsh.js";

export const idl = idlJson as unknown as Idl;
export const codec = new Codec(idl);

export const TOKEN_PROGRAM_ID = new PublicKey("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA");
export const ATA_PROGRAM_ID = new PublicKey("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL");
export const SYSTEM_PROGRAM_ID = new PublicKey("11111111111111111111111111111111");
export const BPF_LOADER_UPGRADEABLE = new PublicKey("BPFLoaderUpgradeab1e11111111111111111111111");

export type PoolStatus = "Open" | "Settling" | "Settled" | "Voided";
export type ParticipationStatus = "Active" | "Succeeded" | "Failed" | "Claimed";

export interface ConfigAccount {
  admin: string;
  oracle: string;
  treasury: string;
  fee_bps: number;
  paused: boolean;
  max_stake: bigint;
  settle_grace_secs: bigint;
  allowed_mints: string[];
  allowed_mint_count: number;
  bump: number;
}

export interface PoolAccount {
  id: bigint;
  creator: string;
  mint: string;
  vault: string;
  kind: "Squad" | "Open";
  mode: "Soft" | "Hard";
  penalty_bps: number;
  fee_bps: number;
  start_ts: bigint;
  end_ts: bigint;
  join_deadline_ts: bigint;
  settle_after_ts: bigint;
  duration_days: number;
  required_days: number;
  goal_hash: Uint8Array;
  max_participants: number;
  participant_count: number;
  settled_count: number;
  pending_claims: number;
  claimed_count: number;
  total_deposits: bigint;
  total_forfeit: bigint;
  total_success_stake: bigint;
  fee_amount: bigint;
  distributable: bigint;
  paid_out: bigint;
  status: PoolStatus;
  bump: number;
  vault_bump: number;
}

export interface ParticipationAccount {
  pool: string;
  user: string;
  stake: bigint;
  tz_offset_minutes: number;
  checkin_bitmap: bigint;
  days_completed: number;
  status: ParticipationStatus;
  device_key_hash: Uint8Array;
  bump: number;
}

export interface CreatePoolParams {
  pool_id: bigint;
  kind: "Squad" | "Open";
  mode: "Soft" | "Hard";
  penalty_bps: number;
  start_ts: bigint;
  duration_days: number;
  required_days: number;
  goal_hash: Uint8Array;
  join_window_secs: bigint;
  max_participants: number;
}

export interface InitConfigParams {
  oracle: string;
  treasury: string;
  fee_bps: number;
  max_stake: bigint;
  settle_grace_secs: bigint;
  allowed_mints: string[];
}

export type AccountMap = Record<string, PublicKey | string>;

function u64le(n: bigint): Buffer {
  const b = Buffer.alloc(8);
  b.writeBigUInt64LE(n);
  return b;
}

export class VowedProgram {
  readonly programId: PublicKey;
  constructor(programId?: string | PublicKey) {
    this.programId = new PublicKey(programId ?? idl.address);
  }

  // ------------------------------------------------------------------ PDAs
  configPda(): PublicKey {
    return PublicKey.findProgramAddressSync([Buffer.from("config")], this.programId)[0];
  }
  poolPda(creator: PublicKey | string, poolId: bigint): PublicKey {
    return PublicKey.findProgramAddressSync(
      [Buffer.from("pool"), new PublicKey(creator).toBuffer(), u64le(poolId)],
      this.programId,
    )[0];
  }
  vaultPda(pool: PublicKey | string): PublicKey {
    return PublicKey.findProgramAddressSync([Buffer.from("vault"), new PublicKey(pool).toBuffer()], this.programId)[0];
  }
  participationPda(pool: PublicKey | string, user: PublicKey | string): PublicKey {
    return PublicKey.findProgramAddressSync(
      [Buffer.from("part"), new PublicKey(pool).toBuffer(), new PublicKey(user).toBuffer()],
      this.programId,
    )[0];
  }
  programDataPda(): PublicKey {
    return PublicKey.findProgramAddressSync([this.programId.toBuffer()], BPF_LOADER_UPGRADEABLE)[0];
  }

  // ------------------------------------------------------------------ instruction building
  /** Builds an instruction using the account order and flags from the IDL, so it cannot drift from the Rust. */
  buildIx(name: string, args: Record<string, unknown>, accounts: AccountMap): TransactionInstruction {
    const def = idl.instructions.find((i) => i.name === name) as
      | (typeof idl.instructions[number] & { accounts: { name: string; writable?: boolean; signer?: boolean; address?: string }[] })
      | undefined;
    if (!def) throw new Error(`unknown instruction ${name}`);
    const keys = def.accounts.map((a) => {
      const supplied = accounts[a.name] ?? a.address;
      if (!supplied) throw new Error(`${name}: missing account ${a.name}`);
      return { pubkey: new PublicKey(supplied), isSigner: !!a.signer, isWritable: !!a.writable };
    });
    return new TransactionInstruction({
      programId: this.programId,
      keys,
      data: Buffer.from(codec.encodeInstruction(name, args)),
    });
  }

  ixInitConfig(admin: PublicKey, params: InitConfigParams): TransactionInstruction {
    return this.buildIx("init_config", { params }, {
      admin,
      config: this.configPda(),
      program: this.programId,
      program_data: this.programDataPda(),
    });
  }

  ixCreatePool(creator: PublicKey, mint: PublicKey, params: CreatePoolParams): TransactionInstruction {
    const pool = this.poolPda(creator, params.pool_id);
    return this.buildIx("create_pool", { params }, {
      creator,
      config: this.configPda(),
      pool,
      mint,
      vault: this.vaultPda(pool),
      token_program: TOKEN_PROGRAM_ID,
    });
  }

  ixJoinPool(
    user: PublicKey,
    pool: PoolRef,
    userToken: PublicKey,
    stake: bigint,
    tzOffsetMinutes: number,
    deviceKeyHash: Uint8Array,
  ): TransactionInstruction {
    return this.buildIx(
      "join_pool",
      { stake, tz_offset_minutes: tzOffsetMinutes, device_key_hash: deviceKeyHash },
      {
        user,
        config: this.configPda(),
        pool: pool.key,
        participation: this.participationPda(pool.key, user),
        mint: pool.mint,
        vault: pool.vault,
        user_token: userToken,
        token_program: TOKEN_PROGRAM_ID,
      },
    );
  }

  ixRecordCheckin(oracle: PublicKey, pool: PublicKey | string, user: PublicKey | string, day: number): TransactionInstruction {
    return this.buildIx("record_checkin", { day_index: day }, {
      oracle,
      config: this.configPda(),
      pool,
      participation: this.participationPda(pool, user),
    });
  }

  ixSettle(pool: PublicKey | string, user: PublicKey | string): TransactionInstruction {
    return this.buildIx("settle_participation", {}, {
      pool,
      participation: this.participationPda(pool, user),
    });
  }

  ixClaim(owner: PublicKey, pool: PoolRef, ownerToken: PublicKey): TransactionInstruction {
    return this.buildIx("claim", {}, {
      owner,
      pool: pool.key,
      participation: this.participationPda(pool.key, owner),
      mint: pool.mint,
      vault: pool.vault,
      owner_token: ownerToken,
      token_program: TOKEN_PROGRAM_ID,
    });
  }

  ixSweep(caller: PublicKey, pool: PoolRef, treasuryToken: PublicKey): TransactionInstruction {
    return this.buildIx("sweep_treasury", {}, {
      caller,
      config: this.configPda(),
      pool: pool.key,
      mint: pool.mint,
      vault: pool.vault,
      treasury_token: treasuryToken,
      token_program: TOKEN_PROGRAM_ID,
    });
  }

  ixVoid(admin: PublicKey, pool: PublicKey | string): TransactionInstruction {
    return this.buildIx("void_pool", {}, { admin, config: this.configPda(), pool });
  }
  ixSetPaused(admin: PublicKey, paused: boolean): TransactionInstruction {
    return this.buildIx("set_paused", { paused }, { admin, config: this.configPda() });
  }
  ixUpdateOracle(admin: PublicKey, newOracle: PublicKey | string): TransactionInstruction {
    return this.buildIx("update_oracle", { new_oracle: new PublicKey(newOracle).toBase58() }, {
      admin,
      config: this.configPda(),
    });
  }

  // ------------------------------------------------------------------ decoding
  decodeConfig(data: Uint8Array): ConfigAccount {
    return codec.decodeAccount<ConfigAccount>("Config", data);
  }
  decodePool(data: Uint8Array): PoolAccount {
    return codec.decodeAccount<PoolAccount>("Pool", data);
  }
  decodeParticipation(data: Uint8Array): ParticipationAccount {
    return codec.decodeAccount<ParticipationAccount>("Participation", data);
  }

  /** Extracts our events from transaction log lines ("Program data: <base64>"). */
  decodeEvents(logs: readonly string[]): { name: string; data: Record<string, unknown> }[] {
    const out: { name: string; data: Record<string, unknown> }[] = [];
    for (const line of logs) {
      const m = /^Program data: (.+)$/.exec(line);
      if (!m) continue;
      try {
        const ev = codec.decodeEvent(Buffer.from(m[1]!, "base64"));
        if (ev) out.push(ev);
      } catch {
        // not ours or malformed: ignore
      }
    }
    return out;
  }

  /** Maps an Anchor error code from a failed transaction to its name, e.g. 6012 -> "DayOutOfRange". */
  errorName(code: number): string | undefined {
    return idl.errors.find((e) => e.code === code)?.name;
  }
}

/** What the backend needs to know about a pool to build join/claim/sweep transactions. */
export interface PoolRef {
  key: PublicKey;
  mint: PublicKey;
  vault: PublicKey;
}

export function poolRef(key: PublicKey | string, pool: PoolAccount): PoolRef {
  return { key: new PublicKey(key), mint: new PublicKey(pool.mint), vault: new PublicKey(pool.vault) };
}
