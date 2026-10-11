// Every bot's activity log (bot_logs, see supabase/005_code_bots.sql): what it was reacting to
// and everything it did. Its owner sees all of it; a server's admins see what it did there.
import { db } from "./server";

export type BotLogKind = "event" | "message" | "react" | "role" | "delete" | "storage" | "log" | "error" | "command";

/** Never throws: a missing log line must not break what the bot was doing. */
export async function logBot(bot: string, kind: BotLogKind, detail: string, where: { server_id?: string | null; channel_id?: string | null } = {}) {
  try {
    await db().from("bot_logs").insert({
      bot, kind, detail: detail.replace(/\s+/g, " ").trim().slice(0, 1000) || "-",
      server_id: where.server_id ?? null, channel_id: where.channel_id ?? null,
    });
    if (Math.random() < 0.02) {        // now and then: forget what's older than two weeks
      await db().from("bot_logs").delete().lt("at", new Date(Date.now() - 14 * 86400_000).toISOString());
    }
  } catch { /* the table may not exist yet (migration 005) */ }
}

export const short = (s: string, n = 200) => (s.length > n ? s.slice(0, n - 1) + "…" : s);
