// PATCH {body}: edit your own group/server message
// DELETE: delete your own message (people who can manage messages can delete anyone's)
import { ApiError, body, db, handler, inboxesOf, notify } from "@/lib/server";
import { MESSAGE_COLUMNS, loadChannelMessage } from "@/lib/chat";
import { previewLater } from "@/lib/embeds";
import { has, P } from "@/lib/perms";

export const PATCH = handler(async (req, ctx) => {
  const { p, m, channel, members } = await loadChannelMessage(req, (await ctx.params).id);
  if (m.sender !== p.uuid || m.kind !== "text") throw new ApiError(403, "You can only edit your own messages");
  const text = String((await body<{ body?: string }>(req)).body ?? "").trim();
  if ((!text && !(m.attachments ?? []).length) || text.length > 2000) throw new ApiError(400, "Messages need 1-2000 characters");
  const { data } = await db().from("channel_messages")
    .update({ body: text, edited_at: new Date().toISOString(), embeds: [] })
    .eq("id", m.id).select(MESSAGE_COLUMNS).single();
  const tell = async () => notify(await inboxesOf(members), "channel", { channel_id: channel.id, server_id: channel.server_id, id: m.id, edited: true });
  await tell();
  previewLater("channel_messages", m.id, text, tell);
  return { message: data };
});

export const DELETE = handler(async (req, ctx) => {
  const { p, m, channel, members, perms } = await loadChannelMessage(req, (await ctx.params).id);
  if (m.sender !== p.uuid && !(channel.server_id && has(perms, P.MANAGE_MESSAGES)))
    throw new ApiError(403, "You can only delete your own messages");
  await db().from("channel_messages").delete().eq("id", m.id);
  await db().from("message_reactions").delete().eq("scope", "c").eq("message_id", m.id);
  await notify(await inboxesOf(members), "channel", { channel_id: channel.id, server_id: channel.server_id, id: m.id, deleted: true });
  return { ok: true };
});
