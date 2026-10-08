// POST {id?}: mark the channel read up to message id (default: everything)
import { body, db, handler, me } from "@/lib/server";
import { channelFor } from "@/lib/chat";

export const POST = handler(async (req, ctx) => {
  const p = await me(req);
  const { channel } = await channelFor((await ctx.params).id, p.uuid);
  let id = Number((await body<{ id?: number }>(req).catch(() => ({} as { id?: number }))).id);
  if (!id) {
    const { data } = await db().from("channel_messages").select("id").eq("channel_id", channel.id)
      .order("id", { ascending: false }).limit(1).maybeSingle();
    id = data?.id ?? 0;
  }
  const { data: cur } = await db().from("channel_reads").select("last_read").eq("channel_id", channel.id).eq("uuid", p.uuid).maybeSingle();
  if (!cur || Number(cur.last_read) < id) {
    await db().from("channel_reads").upsert({ channel_id: channel.id, uuid: p.uuid, last_read: id });
  }
  return { ok: true };
});
