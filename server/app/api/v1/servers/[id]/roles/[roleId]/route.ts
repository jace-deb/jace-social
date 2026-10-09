// PATCH {name?, color?, icon?, permissions?, hoist?, mentionable?}: edit a role below yours (manage roles)
// DELETE: delete a role below yours
import { ApiError, body, db, handler, me } from "@/lib/server";
import { cleanId, cleanName, notifyChanged } from "@/lib/chat";
import { ALL, need, P, requireRoleBelow, serverCtx } from "@/lib/perms";

async function load(req: Request, params: Promise<Record<string, string>>) {
  const p = await me(req);
  const { id: rawId, roleId } = await params;
  const id = cleanId(rawId);
  const sc = await serverCtx(id, p.uuid);
  need(sc.base, P.MANAGE_ROLES);
  const role = sc.roles.find((r) => r.id === cleanId(roleId));
  if (!role) throw new ApiError(404, "Role not found");
  requireRoleBelow(sc, role);
  return { id, sc, role };
}

async function refresh(id: string) {
  const { data: members } = await db().from("server_members").select("uuid").eq("server_id", id);
  await notifyChanged((members ?? []).map((m) => m.uuid), "servers", { kind: "changed", server_id: id });
}

export const PATCH = handler(async (req, ctx) => {
  const { id, sc, role } = await load(req, ctx.params);
  const b = await body<{ name?: string; color?: string | null; icon?: string | null; permissions?: number; hoist?: boolean; mentionable?: boolean }>(req);
  const patch: Record<string, unknown> = {};
  if (b.name !== undefined && !role.is_default) patch.name = cleanName(b.name, "Role name", 32);
  if (b.color !== undefined) patch.color = b.color && /^#[0-9a-fA-F]{6}$/.test(b.color) ? b.color : null;
  if (b.icon !== undefined) patch.icon = b.icon ? String(b.icon).slice(0, 16) : null;
  if (b.hoist !== undefined) patch.hoist = Boolean(b.hoist);
  if (b.mentionable !== undefined) patch.mentionable = Boolean(b.mentionable);
  if (b.permissions !== undefined) {
    const perms = Number(b.permissions) & ALL;
    // you can only add or remove permissions you have yourself
    if (!sc.owner && ((perms ^ role.permissions) & ~sc.base)) throw new ApiError(403, "You can only change permissions you have");
    patch.permissions = perms;
  }
  await db().from("server_roles").update(patch).eq("id", role.id);
  await refresh(id);
  return { ok: true };
});

export const DELETE = handler(async (req, ctx) => {
  const { id, role } = await load(req, ctx.params);
  if (role.is_default) throw new ApiError(400, "@everyone can't be deleted");
  await db().from("server_roles").delete().eq("id", role.id);
  await db().from("channel_overrides").delete().eq("target_type", "role").eq("target_id", role.id);
  await refresh(id);
  return { ok: true };
});
