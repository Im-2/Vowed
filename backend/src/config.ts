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
  /** Reject proofs whose attestation is missing (otherwise they are accepted with a lower trust cap). */
  REQUIRE_ATTESTATION: z.enum(["true", "false"]).default("false").transform((v) => v === "true"),
});

export type Config = z.infer<typeof schema>;

export function loadConfig(env: Record<string, string | undefined> = process.env): Config {
  const parsed = schema.safeParse(env);
  if (!parsed.success) {
    // Messages never include the offending secret values.
    const msg = parsed.error.issues.map((i) => `${i.path.join(".")}: ${i.message}`).join("; ");
    throw new Error(`invalid configuration: ${msg}`);
  }
  return parsed.data;
}
