// PUT: pin a group/server message; DELETE: unpin it (servers: manage messages; group chats: anyone)
import { ApiError, handler, inboxesOf, notify } from "@/lib/server";
import { db } from "@/lib/server";
import { loadChannelMessage, postMessage } from "@/lib/chat";
import { has, P } from "@/lib/perms";

async function pin(req: Request, idParam: string, on: boolean) {
  const { p, m, channel, members, perms } = await loadChannelMessage(req, idParam);
  if (channel.server_id && !has(perms, P.MANAGE_MESSAGES)) throw new ApiError(403, "You don't have permission to pin messages");
  if (on) {
    const { count } = await db().from("channel_messages").select("id", { count: "exact", head: true })
      .eq("channel_id", channel.id).not("pinned_at", "is", null);
    if ((count ?? 0) >= 50) throw new ApiError(400, "A channel can have up to 50 pinned messages");
  }
  await db().from("channel_messages").update({ pinned_at: on ? new Date().toISOString() : null, pinned_by: on ? p.uuid : null }).eq("id", m.id);
  if (on) await postMessage(channel, members, null, `${p.display_name || p.name} pinned a message.`, "system");
  else await notify(await inboxesOf(members), "channel", { channel_id: channel.id, server_id: channel.server_id, id: m.id, edited: true });
  return { ok: true };
}

export const PUT = handler(async (req, ctx) => pin(req, (await ctx.params).id, true));
export const DELETE = handler(async (req, ctx) => pin(req, (await ctx.params).id, false));
