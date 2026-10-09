// POST {name, color?, icon?, permissions?, hoist?, mentionable?}: create a role (manage roles),
//      placed just below your highest role
// PUT {order: [roleId, ...]} (highest first): reorder roles below yours
import { ApiError, body, db, handler, me } from "@/lib/server";
import { cleanId, cleanName, notifyChanged } from "@/lib/chat";
import { ALL, need, P, requireRoleBelow, serverCtx } from "@/lib/perms";

const MAX_ROLES = 100;

export const POST = handler(async (req, ctx) => {
  const p = await me(req);
  const id = cleanId((await ctx.params).id);
  const sc = await serverCtx(id, p.uuid);
  need(sc.base, P.MANAGE_ROLES);
  if (sc.roles.length >= MAX_ROLES) throw new ApiError(400, `Servers can have up to ${MAX_ROLES} roles`);
  const b = await body<{ name?: string; color?: string | null; icon?: string | null; permissions?: number; hoist?: boolean; mentionable?: boolean }>(req);
  const perms = Number(b.permissions ?? 0) & ALL;
  if (!sc.owner && (perms & ~sc.base)) throw new ApiError(403, "You can't give a role permissions you don't have");
  // new roles go just below the creator's highest role (or on top, for the owner)
  const position = sc.owner ? Math.max(0, ...sc.roles.map((r) => r.position)) + 1 : Math.max(1, sc.top);
  for (const r of sc.roles) if (!r.is_default && r.position >= position) await db().from("server_roles").update({ position: r.position + 1 }).eq("id", r.id);
  const { data, error } = await db().from("server_roles").insert({
    server_id: id, name: cleanName(b.name, "Role name", 32), position, permissions: perms,
    color: b.color && /^#[0-9a-fA-F]{6}$/.test(b.color) ? b.color : null, icon: b.icon ? String(b.icon).slice(0, 16) : null,
    hoist: Boolean(b.hoist), mentionable: Boolean(b.mentionable),
  }).select("*").single();
  if (error) throw error;
  const { data: members } = await db().from("server_members").select("uuid").eq("server_id", id);
  await notifyChanged((members ?? []).map((m) => m.uuid), "servers", { kind: "changed", server_id: id });
  return { role: data };
});

export const PUT = handler(async (req, ctx) => {
  const p = await me(req);
  const id = cleanId((await ctx.params).id);
  const sc = await serverCtx(id, p.uuid);
  need(sc.base, P.MANAGE_ROLES);
  const order = ((await body<{ order?: string[] }>(req)).order ?? []).map(String);
  const movable = sc.roles.filter((r) => !r.is_default && (sc.owner || r.position < sc.top));
  if (order.length !== movable.length || !movable.every((r) => order.includes(r.id)))
    throw new ApiError(400, "Send every role you can move, highest first");
  // keep them in the same band of positions they had (below yours)
  const slots = movable.map((r) => r.position).sort((a, b) => b - a);
  await Promise.all(order.map((rid, i) => db().from("server_roles").update({ position: slots[i] }).eq("id", rid)));
  const { data: members } = await db().from("server_members").select("uuid").eq("server_id", id);
  await notifyChanged((members ?? []).map((m) => m.uuid), "servers", { kind: "changed", server_id: id });
  return { ok: true };
});
