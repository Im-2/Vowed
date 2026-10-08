import { Keypair } from "@solana/web3.js";
import bs58 from "bs58";
import { z } from "zod";

/** Parses a secret key given as a JSON array of 64 bytes or a base58 string. Never logs the value. */
export function parseSecretKey(value: string): Keypair {
  const v = value.trim();
  let bytes: Uint8Array;
  if (v.startsWith("[")) {
    bytes = Uint8Array.from(JSON.parse(v) as number[]);
  } else {
    bytes = bs58.decode(v);
  }
  if (bytes.length !== 64) throw new Error("secret key must be 64 bytes");
  return Keypair.fromSecretKey(bytes);
}

const secretKey = z
  .string()
  .min(1)
  .transform((s, ctx) => {
    try {
      return parseSecretKey(s);
    } catch {
      ctx.addIssue({ code: "custom", message: "invalid secret key (value hidden)" });
      return z.NEVER;
    }
  });

const schema = z.object({
  PORT: z.coerce.number().int().min(1).max(65535).default(8787),
  /** Mainnet is deliberately not accepted: it needs explicit approval from the project owner. */
  NETWORK: z.enum(["devnet", "localnet"]).default("devnet"),
  RPC_URL: z.string().url().default("https://api.devnet.solana.com"),
  PROGRAM_ID: z.string().optional(),
  JWT_SECRET: z.string().min(32, "JWT_SECRET must be at least 32 characters"),
  /** Domain shown in the wallet sign-in message and checked on verify. */
  AUTH_DOMAIN: z.string().min(3).default("vowed.app"),
  ORACLE_SECRET_KEY: secretKey,
  /** Pays fees for permissionless settlement/sweep. Separate from the oracle key. */
  CRANK_SECRET_KEY: secretKey,
  DATABASE_PATH: z.string().default("./dev.sqlite"),
  /** "true" enables the background indexer and crank loops. Tests drive them by hand. */
  RUN_JOBS: z.enum(["true", "false"]).default("false").transform((v) => v === "true"),
  FCM_SERVICE_ACCOUNT_JSON: z.string().optional(),
  /**
   * Goal parser. The key stays on the server (never in the app); only the typed goal text is sent to the model.
   * Without a key the deterministic template matcher is used. Config errors never echo values.
   */
  GEMINI_API_KEY: z.string().min(10).optional(),
  GEMINI_MODEL: z.string().min(3).default("gemini-3.5-flash-lite"),
  GEMINI_BASE_URL: z.string().url().default("https://generativelanguage.googleapis.com/v1beta"),
  /** template_first: use a confident template match and ask the model only otherwise (saves free-tier quota); llm_first: always ask first (tests); template_only: never. */
  GOALS_PARSER_MODE: z.enum(["template_first", "llm_first", "template_only"]).default("template_first"),
  GOALS_LLM_TIMEOUT_MS: z.coerce.number().int().min(1000).max(60_000).default(15_000),
  /** Free-tier protection: model calls per wallet per hour, and across everyone per UTC day. */
  GOALS_LLM_PER_WALLET_HOUR: z.coerce.number().int().min(1).default(12),
  GOALS_LLM_DAILY_CAP: z.coerce.number().int().min(1).default(300),
  /**
   * Sample public challenges for Explore. A throwaway devnet key that creates a few real, joinable pools labelled as samples and keeps
   * them topped up (each costs about 0.004 SOL of rent). Off unless the key is set. It holds no authority over anything else.
   */
  SEED_SECRET_KEY: secretKey.optional(),
  SEED_TARGET: z.coerce.number().int().min(0).max(12).default(6),
  /**
   * Test-token faucet (devnet/localnet only). Off unless the mint authority key is set. The key must be the mint authority of BOTH test mints;
   * it also pays the one-time token-account rent for new wallets. It never reaches the repo or the app.
   */
  FAUCET_AUTHORITY_SECRET_KEY: secretKey.optional(),
  FAUCET_USDC_MINT: z.string().optional(),
  FAUCET_SKR_MINT: z.string().optional(),
  /** Base units (6 decimals): 20,000,000 = 20 test tokens. */
  FAUCET_USDC_AMOUNT: z.coerce.bigint().min(1n).default(20_000_000n),
  FAUCET_SKR_AMOUNT: z.coerce.bigint().min(1n).default(20_000_000n),
  /** At most this many claims across all wallets per UTC day. */
  FAUCET_GLOBAL_DAILY_CLAIMS: z.coerce.number().int().min(1).default(200),
  /** A wallet may claim once per this many seconds (default: once per 24 hours). */
  FAUCET_COOLDOWN_SECS: z.coerce.number().int().min(60).default(86_400),
  /**
   * Weekly SKR rewards and SKR-paid perks. The rewards wallet holds the (test) SKR that is paid out and receives perk payments. Off unless
   * its key, the SKR mint and the faucet mint settings are present.
   */
  REWARDS_SECRET_KEY: secretKey.optional(),
  /** base units paid to rank 1, 2, 3 ... (6 decimals: 10,000,000 = 10 tokens) */
  REWARDS_AMOUNTS: z.string().default("10000000,5000000,3000000"),
  REWARDS_MIN_STREAK: z.coerce.number().int().min(1).default(3),
  /** demo pools have minutes-long days, so they are not habit history; only a demo of the rewards turns this on, and the app says so */
  REWARDS_INCLUDE_DEMO: z.enum(["true", "false"]).default("false").transform((v) => v === "true"),
  /** adds clearly labelled SAMPLE rows to the leaderboards so that a new visitor does not see an empty screen; they are never paid */
  LEADERBOARD_SAMPLES: z.enum(["true", "false"]).default("true").transform((v) => v === "true"),
  /** price of one streak freeze in SKR base units */
  PERK_FREEZE_PRICE: z.coerce.bigint().min(1n).default(1_000_000n),
  /** at most this many freezes per challenge */
  PERK_FREEZE_MAX_PER_POOL: z.coerce.number().int().min(1).default(2),
  /** lets the sample proof provider register its own key (devnet demos only) */
  SAMPLE_PROVIDER_ENABLED: z.enum(["true", "false"]).default("false").transform((v) => v === "true"),
  /** Reject proofs whose attestation is missing (otherwise they are accepted with a lower trust cap). */
  REQUIRE_ATTESTATION: z.enum(["true", "false"]).default("false").transform((v) => v === "true"),
});

export type Config = z.infer<typeof schema>;

/** The faucet is usable only when its key and both mints are configured and the network is a test network. */
export function faucetEnabled(c: Config): boolean {
  return Boolean(c.FAUCET_AUTHORITY_SECRET_KEY && c.FAUCET_USDC_MINT && c.FAUCET_SKR_MINT);
}

export function rewardsEnabled(c: Config): boolean {
  return Boolean(c.REWARDS_SECRET_KEY && c.FAUCET_SKR_MINT);
}

export function loadConfig(env: Record<string, string | undefined> = process.env): Config {
  const parsed = schema.safeParse(env);
  if (!parsed.success) {
    // Messages never include the offending secret values.
    const msg = parsed.error.issues.map((i) => `${i.path.join(".")}: ${i.message}`).join("; ");
    throw new Error(`invalid configuration: ${msg}`);
  }
  return parsed.data;
}
