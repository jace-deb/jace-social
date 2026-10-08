// GET: a server's channels (with unread counts), members (with profiles and roles) and your role
// PATCH {name?, description?}: server settings (admins)
// DELETE: delete the server (owner)
import { body, db, handler, me } from "@/lib/server";
import { ApiError } from "@/lib/server";
import { cleanId, cleanName, notifyChanged, profilesById, requireRole, unreadCounts } from "@/lib/chat";

export const GET = handler(async (req, ctx) => {
  const p = await me(req);
  const id = cleanId((await ctx.params).id);
  const role = await requireRole(id, p.uuid, "member");
  const [{ data: s }, { data: chans }, { data: members }] = await Promise.all([
    db().from("servers").select("*").eq("id", id).single(),
    db().from("channels").select("*").eq("server_id", id).order("position").order("created_at"),
    db().from("server_members").select("uuid, role, joined_at").eq("server_id", id),
  ]);
  const people = await profilesById((members ?? []).map((m) => m.uuid));
  const unread = await unreadCounts(p.uuid, (chans ?? []).map((c) => c.id));
  return {
    server: { ...s, invite_code: role === "member" ? null : s.invite_code },   // members ask an admin for invites
    role,
    channels: (chans ?? []).map((c) => ({ ...c, unread: unread[c.id] ?? 0 })),
    members: (members ?? []).map((m) => ({ ...people[m.uuid], role: m.role, joined_at: m.joined_at })),
  };
});

export const PATCH = handler(async (req, ctx) => {
  const p = await me(req);
  const id = cleanId((await ctx.params).id);
  await requireRole(id, p.uuid, "admin");
  const b = await body<{ name?: string; description?: string | null }>(req);
  const patch: Record<string, unknown> = {};
  if (b.name !== undefined) patch.name = cleanName(b.name, "Server name");
  if (b.description !== undefined) {
    const d = (b.description ?? "").trim();
    if (d.length > 300) throw new ApiError(400, "Descriptions can be up to 300 characters");
    patch.description = d || null;
  }
  await db().from("servers").update(patch).eq("id", id);
  const { data: members } = await db().from("server_members").select("uuid").eq("server_id", id);
  await notifyChanged((members ?? []).map((m) => m.uuid), "servers", { kind: "changed", server_id: id });
  return { ok: true };
});

export const DELETE = handler(async (req, ctx) => {
  const p = await me(req);
  const id = cleanId((await ctx.params).id);
  await requireRole(id, p.uuid, "owner");
  const { data: members } = await db().from("server_members").select("uuid").eq("server_id", id);
  await db().from("servers").delete().eq("id", id);
  await notifyChanged((members ?? []).map((m) => m.uuid), "servers", { kind: "deleted", server_id: id });
  return { ok: true };
});
