// GET ?before=<id>: 50 newest messages (oldest first) with the senders' profiles
// POST {body}: send a message to a group chat or server channel
import { ApiError, body, db, handler, me } from "@/lib/server";
import { channelFor, postMessage, profilesById } from "@/lib/chat";

export const GET = handler(async (req, ctx) => {
  const p = await me(req);
  const { channel } = await channelFor((await ctx.params).id, p.uuid);
  const before = Number(new URL(req.url).searchParams.get("before"));
  let q = db().from("channel_messages").select("id, channel_id, sender, body, kind, created_at, edited_at")
    .eq("channel_id", channel.id).order("id", { ascending: false }).limit(50);
  if (before) q = q.lt("id", before);
  const { data, error } = await q;
  if (error) throw error;
  const msgs = (data ?? []).reverse();
  const people = await profilesById([...new Set(msgs.map((m) => m.sender).filter(Boolean) as string[])]);
  return { channel, messages: msgs, people };
});

export const POST = handler(async (req, ctx) => {
  const p = await me(req);
  const { channel, members } = await channelFor((await ctx.params).id, p.uuid);
  const text = String((await body<{ body?: string }>(req)).body ?? "").trim();
  if (!text) throw new ApiError(400, "Message is empty");
  if (text.length > 2000) throw new ApiError(400, "Messages can be up to 2000 characters");
  const minuteAgo = new Date(Date.now() - 60_000).toISOString();
  const { count } = await db().from("channel_messages").select("id", { count: "exact", head: true })
    .eq("sender", p.uuid).gt("created_at", minuteAgo);
  if ((count ?? 0) >= 30) throw new ApiError(429, "Slow down a little - try again in a minute");
  return { message: await postMessage(channel, members, p, text) };
});
