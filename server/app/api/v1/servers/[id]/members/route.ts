// DELETE ?uuid=: leave the server (your own id) or kick someone (admins; not the owner or other admins
//               unless you're the owner). The owner can't leave - delete the server or hand it over.
// PATCH {uuid, role: admin|member|owner}: change someone's role (owner). role owner = hand the server over.
import { ApiError, body, cleanUuid, db, handler, me } from "@/lib/server";
import { cleanId, memberRole, notifyChanged, postMessage, profilesById, requireRole, type Channel } from "@/lib/chat";

async function announce(serverId: string, text: string) {
  const { data: ch } = await db().from("channels").select("*").eq("server_id", serverId).order("position").limit(1).maybeSingle();
  const { data: members } = await db().from("server_members").select("uuid").eq("server_id", serverId);
  const all = (members ?? []).map((m) => m.uuid);
  if (ch) await postMessage(ch as Channel, all, null, text, "system");
  await notifyChanged(all, "servers", { kind: "changed", server_id: serverId });
  return all;
}

export const DELETE = handler(async (req, ctx) => {
  const p = await me(req);
  const id = cleanId((await ctx.params).id);
  const myRole = await requireRole(id, p.uuid, "member");
  const uuid = cleanUuid(new URL(req.url).searchParams.get("uuid") ?? p.uuid);
  const theirRole = uuid === p.uuid ? myRole : await memberRole(id, uuid);
  if (!theirRole) throw new ApiError(404, "They aren't in this server");
  if (uuid === p.uuid && myRole === "owner") throw new ApiError(400, "Owners can't leave - give the server to someone else or delete it");
  if (uuid !== p.uuid) {
    if (myRole === "member") throw new ApiError(403, "Only admins can remove people");
    if (theirRole === "owner" || (theirRole === "admin" && myRole !== "owner")) throw new ApiError(403, "You can't remove them");
  }
  await db().from("server_members").delete().eq("server_id", id).eq("uuid", uuid);
  const who = (await profilesById([uuid]))[uuid]?.name ?? "Someone";
  await announce(id, uuid === p.uuid ? `${who} left the server` : `${who} was removed from the server`);
  await notifyChanged([uuid], "servers", { kind: "removed", server_id: id });
  return { ok: true };
});

export const PATCH = handler(async (req, ctx) => {
  const p = await me(req);
  const id = cleanId((await ctx.params).id);
  await requireRole(id, p.uuid, "owner");
  const b = await body<{ uuid?: string; role?: string }>(req);
  const uuid = cleanUuid(b.uuid);
  if (uuid === p.uuid) throw new ApiError(400, "That's you");
  if (!(await memberRole(id, uuid))) throw new ApiError(404, "They aren't in this server");
  if (!["admin", "member", "owner"].includes(String(b.role))) throw new ApiError(400, "role must be admin, member or owner");
  if (b.role === "owner") {
    await db().from("server_members").update({ role: "admin" }).eq("server_id", id).eq("uuid", p.uuid);
    await db().from("servers").update({ owner: uuid }).eq("id", id);
  }
  await db().from("server_members").update({ role: b.role }).eq("server_id", id).eq("uuid", uuid);
  const who = (await profilesById([uuid]))[uuid]?.name ?? "Someone";
  await announce(id, b.role === "owner" ? `${who} now owns the server` : b.role === "admin" ? `${who} is now an admin` : `${who} is no longer an admin`);
  return { ok: true };
});

