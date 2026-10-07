import { seedPublicChallenges } from "./jobs/seed.js";
import { GeminiClient } from "./goals/gemini.js";
import { readFileSync } from "node:fs";
import { Web3Chain } from "./chain/web3chain.js";
import { loadConfig, type Config } from "./config.js";
import { openDb } from "./db.js";
import { GoogleAttestationVerifier, googleRevocationChecker } from "./devices/attestation.js";
import { buildApp } from "./http/app.js";
import { pruneRateLimits } from "./http/ratelimit.js";
import { pollOnce } from "./indexer.js";
import { runCrank } from "./jobs/crank.js";
import { recordMissedDays, sendReminders } from "./jobs/daily.js";
import { retryPending } from "./oracle.js";
import { VowedProgram } from "./program/client.js";
import { FcmPushSender } from "./push/fcm.js";
import { MemoryPushSender, type PushSender } from "./push/types.js";
import type { Services } from "./services.js";

export function createServices(config: Config): Services {
  const roots = JSON.parse(readFileSync(new URL("./devices/google-roots.json", import.meta.url), "utf8")) as string[];
  const chain = new Web3Chain(config.RPC_URL);
  const push: PushSender = config.FCM_SERVICE_ACCOUNT_JSON ? new FcmPushSender(config.FCM_SERVICE_ACCOUNT_JSON) : new MemoryPushSender();
  return {
    config,
    db: openDb(config.DATABASE_PATH),
    chain,
    program: new VowedProgram(config.PROGRAM_ID),
    oracle: config.ORACLE_SECRET_KEY,
    crank: config.CRANK_SECRET_KEY,
    push,
    attestation: new GoogleAttestationVerifier(roots, googleRevocationChecker()),
    llm: config.GEMINI_API_KEY ? new GeminiClient({ apiKey: config.GEMINI_API_KEY, model: config.GEMINI_MODEL, baseUrl: config.GEMINI_BASE_URL, timeoutMs: config.GOALS_LLM_TIMEOUT_MS }) : null,
    now: () => chain.nowSec(),
    wallNow: () => Math.floor(Date.now() / 1000),
  };
}

/** Background loops (indexer, oracle retries, settlement crank, daily jobs). Each loop catches its own errors. */
export function startJobs(s: Services, log: (msg: string) => void = console.error): () => void {
  const every = (ms: number, name: string, fn: () => Promise<unknown> | unknown) => {
    let running = false;
    const t = setInterval(async () => {
      if (running) return;
      running = true;
      try {
        await fn();
      } catch (e) {
        log(`job ${name} failed: ${e instanceof Error ? e.message : "unknown error"}`);
      } finally {
        running = false;
      }
    }, ms);
    return t;
  };
  const timers = [
    every(10_000, "indexer", () => pollOnce(s)),
    every(30_000, "oracle-retry", () => retryPending(s)),
    every(60_000, "crank", async () => {
      const r = await runCrank(s);
      for (const e of r.errors) log(`crank: ${e}`);
    }),
    every(300_000, "missed-days", () => recordMissedDays(s)),
    every(3_600_000, "reminders", () => sendReminders(s)),
    every(3_600_000, "prune", () => pruneRateLimits(s)),
    // sample public challenges for Explore (only when a sample-wallet key is configured)
    ...(s.config.SEED_SECRET_KEY
      ? [
          every(1_800_000, "seed-explore", async () => {
            const r = await seedPublicChallenges(s);
            for (const e of r.errors) log(`seed-explore: ${e}`);
          }),
        ]
      : []),
  ];
  // a first pass shortly after start (and a retry every minute while the indexer is still reading history), so a fresh deploy lists its samples quickly
  let first: ReturnType<typeof setTimeout> | null = null;
  if (s.config.SEED_SECRET_KEY) {
    let tries = 0;
    const attempt = () => {
      seedPublicChallenges(s)
        .then((r) => {
          r.errors.forEach((e) => log(`seed-explore: ${e}`));
          if (r.errors.some((e) => e.startsWith("waiting for the indexer")) && ++tries < 30) first = setTimeout(attempt, 60_000);
        })
        .catch(() => log("seed-explore failed"));
    };
    first = setTimeout(attempt, 60_000);
  }
  return () => {
    timers.forEach(clearInterval);
    if (first) clearTimeout(first);
  };
}

export async function main(): Promise<void> {
  const config = loadConfig();
  const services = createServices(config);
  const app = await buildApp(services, { logger: true });
  if (config.RUN_JOBS) startJobs(services);
  await app.listen({ port: config.PORT, host: process.env.HOST ?? "0.0.0.0" });
}
