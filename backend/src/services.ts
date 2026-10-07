import type { Keypair } from "@solana/web3.js";
import type { Chain } from "./chain/types.js";
import type { Config } from "./config.js";
import type { Db } from "./db.js";
import type { AttestationVerifier } from "./devices/attestation.js";
import type { GeminiClient } from "./goals/gemini.js";
import type { VowedProgram } from "./program/client.js";
import type { PushSender } from "./push/types.js";

/** Everything the routes and jobs need. Built once at startup; tests build their own with fakes. */
export interface Services {
  config: Config;
  db: Db;
  chain: Chain;
  program: VowedProgram;
  /** Signs record_checkin only. */
  oracle: Keypair;
  /** Pays fees for permissionless settlement and sweeps. Holds no authority. */
  crank: Keypair;
  push: PushSender;
  attestation: AttestationVerifier;
  /** Language model for goal parsing; null when no key is configured. */
  llm: GeminiClient | null;
  /** Cluster time in seconds (the program's clock is authoritative). */
  now(): number;
  /** Real wall-clock seconds: used for session expiry and rate limits so tests can warp the cluster clock. */
  wallNow(): number;
}
