import type { FastifyInstance } from "fastify";
import type { ZodTypeProvider } from "fastify-type-provider-zod";
import { z } from "zod";
import type { Services } from "../services.js";
import { authenticate } from "./auth.js";
import { enforce } from "./ratelimit.js";

/**
 * Things that happened in your squads and nudges aimed at you, newer than `since`. The app polls this while it is open and when a
 * background check runs, and shows a local notification for each new item. It works without any push service; push (FCM) is an
 * extra when a Firebase project is configured.
 */
export function registerNotificationRoutes(app: FastifyInstance, s: Services) {
  const r = app.withTypeProvider<ZodTypeProvider>();
  const auth = authenticate(s);

  r.get(
    "/v1/notifications",
    {
      preHandler: auth,
      schema: {
        tags: ["squads"],
        summary: "New nudges for you and activity in your squads since a time (poll this; push is optional)",
        querystring: z.object({ since: z.coerce.number().int().min(0).default(0), limit: z.coerce.number().int().min(1).max(50).default(30) }),
        response: {
          200: z.object({
            now: z.number(),
            items: z.array(
              z.object({
                id: z.number(),
                kind: z.string(),
                squadId: z.string(),
                squadName: z.string(),
                wallet: z.string(),
                pool: z.string().nullable(),
                data: z.record(z.string(), z.unknown()),
                createdAt: z.number(),
              }),
            ),
          }),
        },
      },
    },
    async (req) => {
      enforce(s, `wallet:${req.wallet}:notifications`, 240, 60);
      const rows = s.db
        .prepare(
          `SELECT f.id, f.kind, f.squad_id, q.name AS squad_name, f.wallet, f.pool, f.data_json, f.created_at
           FROM feed_events f
           JOIN squad_members m ON m.squad_id = f.squad_id AND m.wallet = @me
           JOIN squads q ON q.id = f.squad_id
           WHERE f.created_at > @since AND f.wallet != @me
             AND (f.kind != 'nudge' OR json_extract(f.data_json, '$.recipient') = @me)
           ORDER BY f.id DESC LIMIT @limit`,
        )
        .all({ me: req.wallet!, since: req.query.since, limit: req.query.limit } as never) as unknown as {
        id: number;
        kind: string;
        squad_id: string;
        squad_name: string;
        wallet: string;
        pool: string | null;
        data_json: string;
        created_at: number;
      }[];
      return {
        now: s.wallNow(),
        items: rows.map((e) => ({ id: e.id, kind: e.kind, squadId: e.squad_id, squadName: e.squad_name, wallet: e.wallet, pool: e.pool, data: JSON.parse(e.data_json) as Record<string, unknown>, createdAt: e.created_at })),
      };
    },
  );
}
