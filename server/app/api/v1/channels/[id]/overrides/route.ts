// PUT {target_type: role|member, target_id, allow, deny}: set a channel's permission override
//     for a role or a member (manage roles, and only permissions you have)
// DELETE ?target_type=&target_id=: remove it
import { ApiError, body, cleanUuid, db, handler, me } from "@/lib/server";
import { channelFor, cleanId, notifyChanged } from "@/lib/chat";
import { ALL, need, P, requireRoleBelow } from "@/lib/perms";

async function load(req: Request, params: Promise<Record<string, string>>) {
  const p = await me(req);
  const { channel, ctx: sc } = await channelFor((await params).id, p.uuid);
  if (!sc || !channel.server_id) throw new ApiError(400, "Only server channels have permissions");
  need(sc.base, P.MANAGE_ROLES);
  return { channel, sc };
}

function target(sc: NonNullable<Awaited<ReturnType<typeof load>>["sc"]>, type: string, id: string) {
  if (type === "role") {
    const role = sc.roles.find((r) => r.id === cleanId(id));
    if (!role) throw new ApiError(404, "Role not found");
    requireRoleBelow(sc, role);
    return role.id;
  }
  if (type === "member") return cleanUuid(id);
  throw new ApiError(400, "target_type must be role or member");
}

async function refresh(serverId: string) {
  const { data: members } = await db().from("server_members").select("uuid").eq("server_id", serverId);
  await notifyChanged((members ?? []).map((m) => m.uuid), "servers", { kind: "changed", server_id: serverId });
}

export const PUT = handler(async (req, ctx) => {
  const { channel, sc } = await load(req, ctx.params);
  const b = await body<{ target_type?: string; target_id?: string; allow?: number; deny?: number }>(req);
  const tid = target(sc, String(b.target_type), String(b.target_id));
  const allow = Number(b.allow ?? 0) & ALL & ~P.ADMIN, deny = Number(b.deny ?? 0) & ALL & ~P.ADMIN;
  if (!sc.owner && ((allow | deny) & ~sc.base)) throw new ApiError(403, "You can only set permissions you have");
  if (allow & deny) throw new ApiError(400, "A permission can't be both allowed and denied");
  await db().from("channel_overrides").upsert({ channel_id: channel.id, target_type: b.target_type, target_id: tid, allow, deny });
  await refresh(channel.server_id!);
  return { ok: true };
});

export const DELETE = handler(async (req, ctx) => {
  const { channel, sc } = await load(req, ctx.params);
  const params = new URL(req.url).searchParams;
  const type = String(params.get("target_type"));
  const tid = target(sc, type, String(params.get("target_id")));
  await db().from("channel_overrides").delete().eq("channel_id", channel.id).eq("target_type", type).eq("target_id", tid);
  await refresh(channel.server_id!);
  return { ok: true };
});
