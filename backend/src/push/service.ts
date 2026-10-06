import type { Services } from "../services.js";
import type { PushMessage } from "./types.js";

/** Sends to all of a wallet's registered devices and drops tokens the provider reports as invalid. Returns tokens attempted. */
export async function pushToWallet(s: Services, wallet: string, message: PushMessage): Promise<number> {
  const rows = s.db.prepare("SELECT token FROM push_tokens WHERE wallet = ?").all(wallet) as { token: string }[];
  if (rows.length === 0) return 0;
  const tokens = rows.map((r) => r.token);
  try {
    const { invalidTokens } = await s.push.send(tokens, message);
    for (const t of invalidTokens) s.db.prepare("DELETE FROM push_tokens WHERE token = ?").run(t);
  } catch {
    // push is best effort; never fail a request because FCM is down
  }
  return tokens.length;
}
