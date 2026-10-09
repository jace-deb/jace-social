// DELETE ?uuid=: leave the server (your own id) or kick someone (kick members; only people
//               below your highest role). The owner can't leave - hand it over or delete it.
// PATCH {uuid, nickname?, timeout_until?, roles?, role?: "owner"}: change a member:
//   nickname (yours: change nickname; others: manage nicknames), time out (time out members),
//   roles (manage roles; only roles below yours), or hand the server over (owner).
import { ApiError, body, cleanUuid, db, handler, me } from "@/lib/server";
import { announce, cleanId, memberRole, notifyChanged, profilesById } from "@/lib/chat";
import { has, need, P, requireAbove, requireRoleBelow, serverCtx } from "@/lib/perms";

export const DELETE = handler(async (req, ctx) => {
  const p = await me(req);
  const id = cleanId((await ctx.params).id);
  const sc = await serverCtx(id, p.uuid);
  const uuid = cleanUuid(new URL(req.url).searchParams.get("uuid") ?? p.uuid);
  if (!(await memberRole(id, uuid))) throw new ApiError(404, "They aren't in this server");
  if (uuid === p.uuid && sc.owner) throw new ApiError(400, "Owners can't leave - give the server to someone else or delete it");
  if (uuid !== p.uuid) {
    need(sc.base, P.KICK);
    await requireAbove(sc, uuid);
  }
  await db().from("server_members").delete().eq("server_id", id).eq("uuid", uuid);
  await db().from("server_member_roles").delete().eq("server_id", id).eq("uuid", uuid);
  await db().from("voice_states").delete().eq("uuid", uuid);
  const who = (await profilesById([uuid]))[uuid]?.name ?? "Someone";
  await announce(id, uuid === p.uuid ? `${who} left the server` : `${who} was removed from the server`);
  await notifyChanged([uuid], "servers", { kind: "removed", server_id: id });
  return { ok: true };
});

export const PATCH = handler(async (req, ctx) => {
  const p = await me(req);
  const id = cleanId((await ctx.params).id);
  const sc = await serverCtx(id, p.uuid);
  const b = await body<{ uuid?: string; nickname?: string | null; timeout_until?: string | null; roles?: string[]; role?: string }>(req);
  const uuid = cleanUuid(b.uuid ?? p.uuid);
  if (!(await memberRole(id, uuid))) throw new ApiError(404, "They aren't in this server");
  const who = (await profilesById([uuid]))[uuid]?.name ?? "Someone";
  const patch: Record<string, unknown> = {};

  if (b.nickname !== undefined) {
    need(sc.base, uuid === p.uuid ? P.CHANGE_NICK : P.MANAGE_NICKS);
    if (uuid !== p.uuid) await requireAbove(sc, uuid);
    const nick = String(b.nickname ?? "").trim();
    if (nick.length > 32) throw new ApiError(400, "Nicknames can be up to 32 characters");
    patch.nickname = nick || null;
  }
  if (b.timeout_until !== undefined) {
    need(sc.base, P.TIMEOUT);
    if (uuid === p.uuid) throw new ApiError(400, "You can't time yourself out");
    await requireAbove(sc, uuid);
    const until = b.timeout_until ? new Date(b.timeout_until) : null;
    if (until && (isNaN(until.getTime()) || until.getTime() > Date.now() + 28 * 86400_000))
      throw new ApiError(400, "Timeouts can be up to 28 days");
    patch.timeout_until = until?.toISOString() ?? null;
  }
  if (Object.keys(patch).length) await db().from("server_members").update(patch).eq("server_id", id).eq("uuid", uuid);
  if (b.timeout_until !== undefined) {
    await announce(id, patch.timeout_until ? `${who} was timed out until ${new Date(String(patch.timeout_until)).toUTCString()}` : `${who}'s timeout ended`);
  }

  if (b.roles !== undefined) {
    need(sc.base, P.MANAGE_ROLES);
    if (uuid !== p.uuid) await requireAbove(sc, uuid);
    const want = new Set((b.roles ?? []).map(String));
    const { data: current } = await db().from("server_member_roles").select("role_id").eq("server_id", id).eq("uuid", uuid);
    const have = new Set((current ?? []).map((r) => r.role_id));
    for (const r of sc.roles.filter((r) => !r.is_default && want.has(r.id) !== have.has(r.id))) {
      requireRoleBelow(sc, r);
      if (want.has(r.id)) await db().from("server_member_roles").insert({ server_id: id, uuid, role_id: r.id });
      else await db().from("server_member_roles").delete().eq("server_id", id).eq("uuid", uuid).eq("role_id", r.id);
    }
  }

  if (b.role === "owner") {
    if (!sc.owner) throw new ApiError(403, "Only the owner can hand the server over");
    if (uuid === p.uuid) throw new ApiError(400, "That's you");
    await db().from("servers").update({ owner: uuid }).eq("id", id);
    await db().from("server_members").update({ role: "member" }).eq("server_id", id).eq("uuid", p.uuid);
    await db().from("server_members").update({ role: "owner" }).eq("server_id", id).eq("uuid", uuid);
    await announce(id, `${who} now owns the server`);
  } else if (b.role === "admin" || b.role === "member") {
    // older clients: "admin" = the Admin role
    need(sc.base, P.MANAGE_ROLES);
    await requireAbove(sc, uuid);
    let admin = sc.roles.find((r) => r.name === "Admin" && !r.is_default);
    if (!admin && b.role === "admin") {
      const { data } = await db().from("server_roles").insert({ server_id: id, name: "Admin", color: "#3ddc84", position: 1, permissions: P.ADMIN, hoist: true }).select("*").single();
      admin = data;
    }
    if (admin) {
      requireRoleBelow(sc, admin);
      if (b.role === "admin") await db().from("server_member_roles").upsert({ server_id: id, uuid, role_id: admin.id });
      else await db().from("server_member_roles").delete().eq("server_id", id).eq("uuid", uuid).eq("role_id", admin.id);
    }
  }
  const { data: members } = await db().from("server_members").select("uuid").eq("server_id", id);
  await notifyChanged((members ?? []).map((m) => m.uuid), "servers", { kind: "changed", server_id: id });
  return { ok: true, can: has(sc.base, P.MANAGE_ROLES) };
});
