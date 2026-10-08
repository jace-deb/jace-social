// PATCH {name?, topic?, position?}: edit a server channel (admins)
// DELETE: delete a server channel (admins; a server keeps at least one channel)
import { ApiError, body, db, handler, me } from "@/lib/server";
import { channelFor, channelName, notifyChanged, requireRole } from "@/lib/chat";

export const PATCH = handler(async (req, ctx) => {
  const p = await me(req);
  const { channel, members } = await channelFor((await ctx.params).id, p.uuid);
  if (!channel.server_id) throw new ApiError(400, "Rename group chats with /groups/{id}");
  await requireRole(channel.server_id, p.uuid, "admin");
  const b = await body<{ name?: string; topic?: string | null; position?: number }>(req);
  const patch: Record<string, unknown> = {};
  if (b.name !== undefined) patch.name = channelName(b.name);
  if (b.topic !== undefined) {
    const t = (b.topic ?? "").trim();
    if (t.length > 200) throw new ApiError(400, "Topics can be up to 200 characters");
    patch.topic = t || null;
  }
  if (typeof b.position === "number") patch.position = Math.max(0, Math.min(1000, Math.round(b.position)));
  await db().from("channels").update(patch).eq("id", channel.id);
  await notifyChanged(members, "servers", { kind: "changed", server_id: channel.server_id });
  return { ok: true };
});

export const DELETE = handler(async (req, ctx) => {
  const p = await me(req);
  const { channel, members } = await channelFor((await ctx.params).id, p.uuid);
  if (!channel.server_id) throw new ApiError(400, "Leave group chats with /groups/{id}/members");
  await requireRole(channel.server_id, p.uuid, "admin");
  const { count } = await db().from("channels").select("id", { count: "exact", head: true }).eq("server_id", channel.server_id);
  if ((count ?? 0) <= 1) throw new ApiError(400, "A server needs at least one channel");
  await db().from("channels").delete().eq("id", channel.id);
  await notifyChanged(members, "servers", { kind: "changed", server_id: channel.server_id });
  return { ok: true };
});
