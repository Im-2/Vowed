/**
 * Writes the environment values for the Render dashboard to backend/.devnet/render-env.txt (git-ignored), one NAME=value per line,
 * so you can copy each into Render yourself. Nothing is printed to the terminal and nothing is sent anywhere.
 * A fresh JWT secret is generated; the other keys are the throwaway devnet keys already in backend/.devnet. Delete the file when done.
 *
 *   npx tsx scripts/render-env.ts
 */
import { randomBytes } from "node:crypto";
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { Keypair } from "@solana/web3.js";

const DIR = new URL("../.devnet/", import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, "$1");
const one = (f: string) => JSON.stringify(JSON.parse(readFileSync(`${DIR}${f}`, "utf8")));
const pub = (f: string) => Keypair.fromSecretKey(Uint8Array.from(JSON.parse(readFileSync(`${DIR}${f}`, "utf8")) as number[])).publicKey.toBase58();
for (const f of ["oracle.json", "crank.json", "faucet.json", "seeder.json", "mint-usdc-test.json", "mint-skr-test.json"]) {
  if (!existsSync(`${DIR}${f}`)) throw new Error(`missing backend/.devnet/${f}`);
}
const lines = [
  `JWT_SECRET=${randomBytes(32).toString("hex")}`,
  `ORACLE_SECRET_KEY=${one("oracle.json")}`,
  `CRANK_SECRET_KEY=${one("crank.json")}`,
  `SEED_SECRET_KEY=${one("seeder.json")}`,
  `FAUCET_AUTHORITY_SECRET_KEY=${one("faucet.json")}`,
  `FAUCET_USDC_MINT=${pub("mint-usdc-test.json")}`,
  `FAUCET_SKR_MINT=${pub("mint-skr-test.json")}`,
  existsSync(`${DIR}gemini-key.txt`) ? `GEMINI_API_KEY=${readFileSync(`${DIR}gemini-key.txt`, "utf8").trim()}` : "# GEMINI_API_KEY=  (optional; no key file found)",
];
writeFileSync(`${DIR}render-env.txt`, lines.join("\n") + "\n");
console.log(`wrote ${lines.length} lines to backend/.devnet/render-env.txt (not shown here). Delete it after you have copied the values.`);
