// Reactions on messages: scope "c" = group/server messages, "d" = direct messages.
import { db } from "./server";

export type ReactionSummary = { emoji: string; count: number; me: boolean };

/** {messageId: [{emoji, count, me}]} for a page of messages. */
export async function reactionsFor(scope: "c" | "d", ids: number[], uuid: string): Promise<Record<number, ReactionSummary[]>> {
  if (!ids.length) return {};
  const { data } = await db().from("message_reactions").select("message_id, uuid, emoji, created_at")
    .eq("scope", scope).in("message_id", ids).order("created_at");
  const out: Record<number, ReactionSummary[]> = {};
  for (const r of data ?? []) {
    const list = (out[r.message_id] ??= []);
    let e = list.find((x) => x.emoji === r.emoji);
    if (!e) list.push(e = { emoji: r.emoji, count: 0, me: false });
    e.count++;
    if (r.uuid === uuid) e.me = true;
  }
  return out;
}

/** The messages these replies point at, shortened for the "replying to" line. */
export async function repliedTo(table: "channel_messages" | "messages", ids: (number | null)[]) {
  const want = [...new Set(ids.filter(Boolean) as number[])];
  if (!want.length) return {} as Record<number, { id: number; sender: string | null; body: string; attachments: number }>;
  const { data } = await db().from(table).select("id, sender, body, attachments").in("id", want);
  return Object.fromEntries((data ?? []).map((m) => [m.id, {
    id: m.id, sender: m.sender, body: String(m.body).slice(0, 120), attachments: (m.attachments ?? []).length,
  }]));
}
