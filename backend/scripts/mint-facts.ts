/** Read-only: owner program, decimals, size and authorities of the stake mints on devnet. Prints public facts only. */
import { Connection, PublicKey } from "@solana/web3.js";
const conn = new Connection("https://api.devnet.solana.com", "confirmed");
const mints: Record<string, string> = {
  "Circle devnet USDC": "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU",
  "tUSDC (test USDC)": "C6pXRRmoHsf7Mqa1ZrW3JfspyknhSR1cRqHMUqao63Hv",
  "tSKR (test SKR)": "J6X9udvWis7jYpVHic3Kn5iTG3ac6gbBeFixnYKPUkHB",
};
for (const [name, addr] of Object.entries(mints)) {
  const info = await conn.getAccountInfo(new PublicKey(addr));
  if (!info) { console.log(name, "NOT FOUND"); continue; }
  const d = info.data;
  const hasMintAuth = d.readUInt32LE(0) === 1;
  const hasFreeze = d.readUInt32LE(46) === 1;
  console.log(name, "| owner", info.owner.toBase58(), "| bytes", d.length, "| decimals", d[44], "| mint authority", hasMintAuth ? new PublicKey(d.subarray(4, 36)).toBase58() : "none", "| freeze authority", hasFreeze ? new PublicKey(d.subarray(50, 82)).toBase58() : "none");
}
const meta = await (await fetch("https://vowed-backend.onrender.com/v1/meta")).json() as { config: { allowedMints: string[]; demoMints: string[]; maxStake: string; demoMaxStake: string } };
console.log("program allowedMints:", meta.config.allowedMints.map((m) => Object.entries(mints).find(([, a]) => a === m)?.[0] ?? m).join(", "));
console.log("program demoMints:", meta.config.demoMints.map((m) => Object.entries(mints).find(([, a]) => a === m)?.[0] ?? m).join(", "));
console.log("maxStake", meta.config.maxStake, "demoMaxStake", meta.config.demoMaxStake);
