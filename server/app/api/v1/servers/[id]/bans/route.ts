// GET: the server's bans (ban members)
// POST {uuid, reason?, delete_messages?}: ban someone (removes them; they can't rejoin)
// DELETE ?uuid=: unban
import { ApiError, body, cleanUuid, db, handler, me } from "@/lib/server";
import { announce, cleanId, memberRole, notifyChanged, profilesById } from "@/lib/chat";
import { need, P, requireAbove, serverCtx } from "@/lib/perms";

export const GET = handler(async (req, ctx) => {
  const p = await me(req);
  const id = cleanId((await ctx.params).id);
  need((await serverCtx(id, p.uuid)).base, P.BAN);
  const { data } = await db().from("server_bans").select("*").eq("server_id", id).order("created_at", { ascending: false });
  const people = await profilesById((data ?? []).map((b) => b.uuid));
  return { bans: (data ?? []).map((b) => ({ ...b, person: people[b.uuid] })) };
});

export const POST = handler(async (req, ctx) => {
  const p = await me(req);
  const id = cleanId((await ctx.params).id);
  const sc = await serverCtx(id, p.uuid);
  need(sc.base, P.BAN);
  const b = await body<{ uuid?: string; reason?: string; delete_messages?: boolean }>(req);
  const uuid = cleanUuid(b.uuid);
  if (uuid === p.uuid) throw new ApiError(400, "You can't ban yourself");
  if (sc.server.owner === uuid) throw new ApiError(403, "You can't ban the owner");
  if (await memberRole(id, uuid)) await requireAbove(sc, uuid);
  const reason = String(b.reason ?? "").trim().slice(0, 200) || null;
  await db().from("server_bans").upsert({ server_id: id, uuid, reason, banned_by: p.uuid });
  await db().from("server_members").delete().eq("server_id", id).eq("uuid", uuid);
  await db().from("server_member_roles").delete().eq("server_id", id).eq("uuid", uuid);
  await db().from("voice_states").delete().eq("uuid", uuid);
  if (b.delete_messages) {
    const { data: chans } = await db().from("channels").select("id").eq("server_id", id);
    const since = new Date(Date.now() - 7 * 86400_000).toISOString();
    if (chans?.length) await db().from("channel_messages").delete().eq("sender", uuid).in("channel_id", chans.map((c) => c.id)).gt("created_at", since);
  }
  const who = (await profilesById([uuid]))[uuid]?.name ?? "Someone";
  await announce(id, `${who} was banned`);
  await notifyChanged([uuid], "servers", { kind: "removed", server_id: id });
  return { ok: true };
});

export const DELETE = handler(async (req, ctx) => {
  const p = await me(req);
  const id = cleanId((await ctx.params).id);
  need((await serverCtx(id, p.uuid)).base, P.BAN);
  const uuid = cleanUuid(new URL(req.url).searchParams.get("uuid"));
  await db().from("server_bans").delete().eq("server_id", id).eq("uuid", uuid);
  return { ok: true };
});
