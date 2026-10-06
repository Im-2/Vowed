/**
 * Firebase Cloud Messaging HTTP v1 sender without the Firebase SDK. FCM is free; it needs a Firebase project and a
 * service-account key (FCM_SERVICE_ACCOUNT_JSON), which the project owner creates. Until then the backend uses the
 * in-memory sender. Covered by a unit test with a fake fetch; not yet exercised against Google (needs the account).
 */
import { importPKCS8, SignJWT } from "jose";
import type { PushMessage, PushSender } from "./types.js";

interface ServiceAccount {
  project_id: string;
  client_email: string;
  private_key: string;
  token_uri?: string;
}

export class FcmPushSender implements PushSender {
  private readonly account: ServiceAccount;
  private token: { value: string; expiresAt: number } | null = null;
  constructor(serviceAccountJson: string, private readonly fetchImpl: typeof fetch = fetch) {
    this.account = JSON.parse(serviceAccountJson) as ServiceAccount;
    if (!this.account.project_id || !this.account.client_email || !this.account.private_key) throw new Error("incomplete FCM service account");
  }

  private async accessToken(): Promise<string> {
    const nowMs = Date.now();
    if (this.token && this.token.expiresAt - 60_000 > nowMs) return this.token.value;
    const tokenUri = this.account.token_uri ?? "https://oauth2.googleapis.com/token";
    const key = await importPKCS8(this.account.private_key, "RS256");
    const assertion = await new SignJWT({ scope: "https://www.googleapis.com/auth/firebase.messaging" })
      .setProtectedHeader({ alg: "RS256", typ: "JWT" })
      .setIssuer(this.account.client_email)
      .setSubject(this.account.client_email)
      .setAudience(tokenUri)
      .setIssuedAt()
      .setExpirationTime("55m")
      .sign(key);
    const res = await this.fetchImpl(tokenUri, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion }),
    });
    if (!res.ok) throw new Error(`FCM auth failed: ${res.status}`);
    const body = (await res.json()) as { access_token: string; expires_in: number };
    this.token = { value: body.access_token, expiresAt: nowMs + body.expires_in * 1000 };
    return body.access_token;
  }

  async send(tokens: string[], message: PushMessage): Promise<{ invalidTokens: string[] }> {
    const bearer = await this.accessToken();
    const invalid: string[] = [];
    for (const token of tokens) {
      const res = await this.fetchImpl(`https://fcm.googleapis.com/v1/projects/${this.account.project_id}/messages:send`, {
        method: "POST",
        headers: { authorization: `Bearer ${bearer}`, "content-type": "application/json" },
        body: JSON.stringify({ message: { token, notification: { title: message.title, body: message.body }, data: message.data ?? {}, android: { priority: "HIGH" } } }),
      });
      if (res.status === 404 || res.status === 400) {
        const body = (await res.json().catch(() => ({}))) as { error?: { status?: string } };
        if (body.error?.status === "NOT_FOUND" || body.error?.status === "UNREGISTERED" || body.error?.status === "INVALID_ARGUMENT") invalid.push(token);
      }
    }
    return { invalidTokens: invalid };
  }
}
