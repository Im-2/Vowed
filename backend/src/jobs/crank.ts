import { PublicKey } from "@solana/web3.js";
import { ChainError } from "../chain/types.js";
import { signAndSend } from "../chain/tx.js";
import { syncParticipation, syncPool, type ChallengeRow } from "../challenges/sync.js";
import { processLogs } from "../indexer.js";
import type { Services } from "../services.js";
import { ataAddress, ixCreateAtaIdempotent } from "../util/token.js";

export interface CrankReport {
  settled: number;
  swept: number;
  errors: string[];
}

/**
 * Settles every participation of pools whose settlement time has passed, then sweeps leftovers to the treasury once all
 * non-zero claims are done. Both instructions are permissionless; the crank key only pays fees.
 */
export async function runCrank(s: Services): Promise<CrankReport> {
  const report: CrankReport = { settled: 0, swept: 0, errors: [] };
  const now = s.now();
  const due = s.db.prepare("SELECT * FROM challenges WHERE status IN ('Open','Settling') AND settle_after_ts <= ?").all(now) as unknown as ChallengeRow[];

  for (const c of due) {
    const actives = s.db.prepare("SELECT wallet FROM participants WHERE pool = ? AND status = 'Active'").all(c.pool) as { wallet: string }[];
    for (const a of actives) {
      try {
        const res = await signAndSend(s.chain, [s.program.ixSettle(c.pool, a.wallet)], [s.crank]);
        await processLogs(s, res.signature, res.logs);
        report.settled++;
      } catch (e) {
        if (e instanceof ChainError && e.anchorError?.code === "AlreadySettled") {
          await syncParticipation(s, c.pool, a.wallet);
          continue;
        }
        report.errors.push(`settle ${c.pool}/${a.wallet}: ${describe(e)}`);
      }
    }
    await syncPool(s, c.pool);
  }

  // sweep: settled pools with no pending claims and something left in the vault
  const sweepable = s.db.prepare("SELECT * FROM challenges WHERE status = 'Settled' AND pending_claims = 0").all() as unknown as ChallengeRow[];
  if (sweepable.length > 0) {
    const cfgAcc = await s.chain.getAccount(s.program.configPda().toBase58());
    if (cfgAcc) {
      const treasury = new PublicKey(s.program.decodeConfig(cfgAcc.data).treasury);
      for (const c of sweepable) {
        const vaultAcc = await s.chain.getAccount(c.vault);
        if (!vaultAcc || vaultAcc.data.length < 72 || Buffer.from(vaultAcc.data.subarray(64, 72)).readBigUInt64LE() === 0n) continue;
        try {
          const mint = new PublicKey(c.mint);
          const treasuryToken = ataAddress(treasury, mint);
          const res = await signAndSend(
            s.chain,
            [ixCreateAtaIdempotent(s.crank.publicKey, treasury, mint), s.program.ixSweep(s.crank.publicKey, { key: new PublicKey(c.pool), mint, vault: new PublicKey(c.vault) }, treasuryToken)],
            [s.crank],
          );
          await processLogs(s, res.signature, res.logs);
          report.swept++;
        } catch (e) {
          if (e instanceof ChainError && e.anchorError?.code === "NothingToSweep") continue;
          report.errors.push(`sweep ${c.pool}: ${describe(e)}`);
        }
      }
    }
  }
  return report;
}

function describe(e: unknown): string {
  if (e instanceof ChainError) return e.anchorError?.code ?? e.message.slice(0, 160);
  return e instanceof Error ? e.message.slice(0, 160) : "unknown error";
}
