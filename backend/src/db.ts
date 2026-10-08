import { DatabaseSync } from "node:sqlite";

export type Db = DatabaseSync;

const MIGRATIONS: string[] = [
  `
CREATE TABLE users (
  wallet TEXT PRIMARY KEY,
  display_name TEXT,
  created_at INTEGER NOT NULL
);
CREATE TABLE nonces (
  nonce TEXT PRIMARY KEY,
  purpose TEXT NOT NULL,
  wallet TEXT,
  expires_at INTEGER NOT NULL,
  used_at INTEGER
);
CREATE TABLE devices (
  id TEXT NOT NULL,                -- hex sha256 of the P-256 public key (SPKI DER)
  wallet TEXT NOT NULL,
  pubkey BLOB NOT NULL,            -- SPKI DER
  attestation_level TEXT NOT NULL, -- strongbox | tee | software | none
  trust_cap TEXT NOT NULL,         -- highest proof trust tier this device can earn: high | medium | low
  attestation_note TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (id, wallet)
);
CREATE TABLE challenges (
  pool TEXT PRIMARY KEY,
  creator TEXT NOT NULL,
  pool_id TEXT NOT NULL,
  mint TEXT NOT NULL,
  vault TEXT NOT NULL,
  kind TEXT NOT NULL,
  mode TEXT NOT NULL,
  penalty_bps INTEGER NOT NULL,
  fee_bps INTEGER NOT NULL,
  start_ts INTEGER NOT NULL,
  end_ts INTEGER NOT NULL,
  join_deadline_ts INTEGER NOT NULL,
  settle_after_ts INTEGER NOT NULL,
  duration_days INTEGER NOT NULL,
  required_days INTEGER NOT NULL,
  goal_hash TEXT NOT NULL,         -- hex sha256 of the canonical GoalPlan JSON
  max_participants INTEGER NOT NULL,
  participant_count INTEGER NOT NULL,
  settled_count INTEGER NOT NULL,
  pending_claims INTEGER NOT NULL,
  total_deposits TEXT NOT NULL,    -- u64 as decimal string
  total_forfeit TEXT NOT NULL,
  total_success_stake TEXT NOT NULL,
  distributable TEXT NOT NULL,
  status TEXT NOT NULL,            -- Open | Settling | Settled | Voided
  plan_json TEXT,                  -- GoalPlan, only after /challenges/plan verified the hash
  squad_id TEXT,
  updated_at INTEGER NOT NULL
);
CREATE INDEX idx_challenges_status ON challenges(status, settle_after_ts);
CREATE TABLE participants (
  pool TEXT NOT NULL,
  wallet TEXT NOT NULL,
  stake TEXT NOT NULL,
  tz_offset_minutes INTEGER NOT NULL,
  checkin_bitmap TEXT NOT NULL,
  days_completed INTEGER NOT NULL,
  status TEXT NOT NULL,            -- Active | Succeeded | Failed | Claimed
  device_key_hash TEXT NOT NULL,   -- hex
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (pool, wallet)
);
CREATE TABLE proof_sessions (
  id TEXT PRIMARY KEY,
  wallet TEXT NOT NULL,
  pool TEXT NOT NULL,
  day_index INTEGER NOT NULL,
  proof_type TEXT NOT NULL,
  nonce TEXT NOT NULL,
  expires_at INTEGER NOT NULL,
  status TEXT NOT NULL,            -- open | passed | rejected
  result_json TEXT,
  created_at INTEGER NOT NULL
);
CREATE INDEX idx_sessions_wallet ON proof_sessions(wallet, pool, day_index);
CREATE TABLE proofs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  session_id TEXT NOT NULL UNIQUE,
  wallet TEXT NOT NULL,
  pool TEXT NOT NULL,
  day_index INTEGER NOT NULL,
  proof_type TEXT NOT NULL,
  trust_tier TEXT NOT NULL,
  evidence_hash TEXT NOT NULL,     -- hash only; no raw evidence is ever stored
  status TEXT NOT NULL,            -- accepted | rejected
  reject_reason TEXT,
  hour_of_day INTEGER,
  created_at INTEGER NOT NULL
);
CREATE TABLE checkins (
  pool TEXT NOT NULL,
  wallet TEXT NOT NULL,
  day_index INTEGER NOT NULL,
  status TEXT NOT NULL,            -- pending | confirmed | failed
  tx_sig TEXT,
  attempts INTEGER NOT NULL DEFAULT 0,
  last_error TEXT,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (pool, wallet, day_index)
);
CREATE TABLE squads (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  owner TEXT NOT NULL,
  invite_code TEXT NOT NULL UNIQUE,
  created_at INTEGER NOT NULL
);
CREATE TABLE squad_members (
  squad_id TEXT NOT NULL,
  wallet TEXT NOT NULL,
  joined_at INTEGER NOT NULL,
  PRIMARY KEY (squad_id, wallet)
);
CREATE TABLE feed_events (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  squad_id TEXT NOT NULL,
  pool TEXT,
  wallet TEXT NOT NULL,
  kind TEXT NOT NULL,              -- checked_in | missed | joined | nudge | settled
  data_json TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX idx_feed_squad ON feed_events(squad_id, id);
CREATE TABLE nudges (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  squad_id TEXT NOT NULL,
  sender TEXT NOT NULL,
  recipient TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE TABLE push_tokens (
  token TEXT PRIMARY KEY,
  wallet TEXT NOT NULL,
  platform TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE TABLE coach_stats (
  wallet TEXT NOT NULL,
  pool TEXT NOT NULL,
  category TEXT NOT NULL,
  difficulty INTEGER NOT NULL,
  required_days INTEGER NOT NULL,
  duration_days INTEGER NOT NULL,
  days_completed INTEGER NOT NULL,
  outcome TEXT NOT NULL,           -- succeeded | failed
  finished_at INTEGER NOT NULL,
  PRIMARY KEY (wallet, pool)
);
CREATE TABLE rate_limits (
  key TEXT NOT NULL,
  window_start INTEGER NOT NULL,
  count INTEGER NOT NULL,
  PRIMARY KEY (key, window_start)
);
CREATE TABLE idempotency (
  wallet TEXT NOT NULL,
  endpoint TEXT NOT NULL,
  idem_key TEXT NOT NULL,
  response_json TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (wallet, endpoint, idem_key)
);
CREATE TABLE kv (
  k TEXT PRIMARY KEY,
  v TEXT NOT NULL
);
CREATE TABLE processed_txs (
  signature TEXT PRIMARY KEY,
  processed_at INTEGER NOT NULL
);
CREATE TABLE plans (
  goal_hash TEXT NOT NULL,         -- hex sha256 of the canonical GoalPlan JSON
  creator TEXT NOT NULL,
  plan_json TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (goal_hash, creator)
);
`,
  // 2: demo pools (minutes-long days)
  `
ALTER TABLE challenges ADD COLUMN is_demo INTEGER NOT NULL DEFAULT 0;
ALTER TABLE challenges ADD COLUMN day_secs INTEGER NOT NULL DEFAULT 86400;
`,
  // 3: test-token faucet claims
  `
CREATE TABLE faucet_claims (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  wallet TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  day INTEGER NOT NULL,                 -- UTC day number of created_at, for the global daily cap
  status TEXT NOT NULL,                 -- pending | sent | failed (failed claims do not count toward any limit)
  usdc TEXT NOT NULL,
  skr TEXT NOT NULL,
  signature TEXT,
  error TEXT
);
CREATE INDEX faucet_claims_wallet ON faucet_claims (wallet, created_at);
CREATE INDEX faucet_claims_day ON faucet_claims (day, status);
`,
  // 4: cache of language-model goal parses (keyed by a hash of the normalised text; only model answers are cached)
  `
CREATE TABLE goal_cache (
  text_hash TEXT PRIMARY KEY,
  result_json TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
`,
  // 5: Explore: how each pool is listed, and reports against public challenges
  `
CREATE TABLE challenge_meta (
  pool TEXT PRIMARY KEY,
  creator TEXT NOT NULL,
  visibility TEXT NOT NULL DEFAULT 'private',   -- public | private (squad challenges are always private)
  title TEXT NOT NULL,                           -- the goal text as shown (also what moderation checked)
  category TEXT NOT NULL,
  proof_type TEXT,
  seeded INTEGER NOT NULL DEFAULT 0,             -- 1 for sample challenges created by the Vowed team
  hidden INTEGER NOT NULL DEFAULT 0,             -- 1 after enough reports
  created_at INTEGER NOT NULL
);
CREATE INDEX idx_meta_listing ON challenge_meta (visibility, hidden, created_at);
CREATE TABLE reports (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  pool TEXT NOT NULL,
  reporter TEXT NOT NULL,
  reason TEXT NOT NULL,
  note TEXT,
  created_at INTEGER NOT NULL,
  UNIQUE (pool, reporter)
);
`,
  // 6: Phase 8: weekly SKR rewards, streak freezes paid in SKR, and signed attestations from proof providers
  `
CREATE TABLE rewards (
  week INTEGER NOT NULL,                 -- unix week number (days since 1970 divided by 7) that the reward is FOR
  wallet TEXT NOT NULL,
  rank INTEGER NOT NULL,
  streak INTEGER NOT NULL,
  amount TEXT NOT NULL,                  -- token base units
  status TEXT NOT NULL,                  -- pending | sent | failed (a failed row is retried on the next run)
  signature TEXT,
  error TEXT,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (week, wallet)
);
CREATE TABLE freezes (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  wallet TEXT NOT NULL,
  pool TEXT NOT NULL,
  day_index INTEGER NOT NULL,
  signature TEXT NOT NULL UNIQUE,        -- the SKR payment that bought it; one payment buys one freeze, once
  amount TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  UNIQUE (wallet, pool, day_index)
);
CREATE TABLE provider_keys (
  provider_id TEXT NOT NULL,
  key_id TEXT NOT NULL,
  public_key TEXT NOT NULL,              -- base64 SPKI (P-256) or raw 32 bytes (Ed25519)
  algorithm TEXT NOT NULL,               -- ES256 | EdDSA
  wallet TEXT,                           -- set for a key a person registered for themselves; null for a registry key
  created_at INTEGER NOT NULL,
  PRIMARY KEY (provider_id, key_id)
);
CREATE TABLE attestations (
  nonce TEXT PRIMARY KEY,
  provider_id TEXT NOT NULL,
  wallet TEXT NOT NULL,
  metric TEXT NOT NULL,
  value REAL NOT NULL,
  window_start INTEGER NOT NULL,
  window_end INTEGER NOT NULL,
  accepted_at INTEGER NOT NULL
);
`,
  // 2026-10: a person can hide themselves from the public leaderboards (they are still paid)
  `ALTER TABLE users ADD COLUMN leaderboard_hidden INTEGER NOT NULL DEFAULT 0;`,
  // 2026-10: one small devnet SOL gift per wallet, with its own ledger for the caps
  `CREATE TABLE sol_drips (
  wallet TEXT PRIMARY KEY,
  lamports TEXT NOT NULL,
  status TEXT NOT NULL,                 -- pending | sent | failed
  day INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  signature TEXT,
  error TEXT
);`,
];

export function openDb(path: string): Db {
  const db = new DatabaseSync(path);
  db.exec("PRAGMA journal_mode = WAL; PRAGMA foreign_keys = ON; PRAGMA busy_timeout = 5000;");
  migrate(db);
  return db;
}

function migrate(db: Db): void {
  db.exec("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)");
  const row = db.prepare("SELECT MAX(version) AS v FROM schema_version").get() as { v: number | null };
  let version = row.v ?? 0;
  for (; version < MIGRATIONS.length; version++) {
    db.exec("BEGIN");
    try {
      db.exec(MIGRATIONS[version]!);
      db.prepare("INSERT INTO schema_version (version) VALUES (?)").run(version + 1);
      db.exec("COMMIT");
    } catch (e) {
      db.exec("ROLLBACK");
      throw e;
    }
  }
}

export function kvGet(db: Db, k: string): string | undefined {
  const r = db.prepare("SELECT v FROM kv WHERE k = ?").get(k) as { v: string } | undefined;
  return r?.v;
}
export function kvSet(db: Db, k: string, v: string): void {
  db.prepare("INSERT INTO kv (k, v) VALUES (?, ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v").run(k, v);
}
