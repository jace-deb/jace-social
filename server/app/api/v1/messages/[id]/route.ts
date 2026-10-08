// PATCH {body}: edit your own group/server message
// DELETE: delete your own message (server admins can delete anyone's)
import { ApiError, body, db, handler, me } from "@/lib/server";
import { channelFor, memberRole } from "@/lib/chat";
import { notify, inboxesOf } from "@/lib/server";

async function load(req: Request, idParam: string) {
  const p = await me(req);
  const id = Number(idParam);
  const { data: m } = await db().from("channel_messages").select("*").eq("id", id).maybeSingle();
  if (!m) throw new ApiError(404, "Message not found");
  const { channel, members } = await channelFor(m.channel_id, p.uuid);
  return { p, m, channel, members };
}

export const PATCH = handler(async (req, ctx) => {
  const { p, m, channel, members } = await load(req, (await ctx.params).id);
  if (m.sender !== p.uuid || m.kind !== "text") throw new ApiError(403, "You can only edit your own messages");
  const text = String((await body<{ body?: string }>(req)).body ?? "").trim();
  if (!text || text.length > 2000) throw new ApiError(400, "Messages need 1-2000 characters");
  const { data } = await db().from("channel_messages").update({ body: text, edited_at: new Date().toISOString() })
    .eq("id", m.id).select("*").single();
  await notify(await inboxesOf(members), "channel", { channel_id: channel.id, server_id: channel.server_id, id: m.id, edited: true });
  return { message: data };
});

export const DELETE = handler(async (req, ctx) => {
  const { p, m, channel, members } = await load(req, (await ctx.params).id);
  const admin = channel.server_id && ["owner", "admin"].includes((await memberRole(channel.server_id, p.uuid)) ?? "");
  if (m.sender !== p.uuid && !admin) throw new ApiError(403, "You can only delete your own messages");
  await db().from("channel_messages").delete().eq("id", m.id);
  await notify(await inboxesOf(members), "channel", { channel_id: channel.id, server_id: channel.server_id, id: m.id, deleted: true });
  return { ok: true };
});
