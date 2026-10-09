// GET: a channel's pinned messages (newest pin first) with their senders
import { db, handler, me } from "@/lib/server";
import { MESSAGE_COLUMNS, channelFor, profilesById } from "@/lib/chat";

export const GET = handler(async (req, ctx) => {
  const p = await me(req);
  const { channel } = await channelFor((await ctx.params).id, p.uuid);
  const { data } = await db().from("channel_messages").select(MESSAGE_COLUMNS).eq("channel_id", channel.id)
    .not("pinned_at", "is", null).order("pinned_at", { ascending: false });
  const msgs = (data ?? []) as unknown as { sender: string | null }[];
  return { messages: msgs, people: await profilesById([...new Set(msgs.map((m) => m.sender).filter(Boolean) as string[])]) };
});
