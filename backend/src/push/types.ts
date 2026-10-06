export interface PushMessage {
  title: string;
  body: string;
  /** Small string map delivered with the notification. Never contains goal text or letter content. */
  data?: Record<string, string>;
}

export interface PushSender {
  /** Returns the tokens that should be deleted (invalid / unregistered). */
  send(tokens: string[], message: PushMessage): Promise<{ invalidTokens: string[] }>;
}

/** Development sender: logs to memory only. Replaced by the FCM sender once a Firebase project exists. */
export class MemoryPushSender implements PushSender {
  readonly sent: { tokens: string[]; message: PushMessage }[] = [];
  async send(tokens: string[], message: PushMessage) {
    this.sent.push({ tokens, message });
    return { invalidTokens: [] };
  }
}
