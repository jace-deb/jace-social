// Server roles and permissions, like Discord's: every server has an @everyone role,
// members can have more roles, and channels can override permissions for a role or
// a member. The owner and the administrator permission can do everything.
import { ApiError, db } from "./server";

export const P = {
  VIEW: 1, SEND: 2, ATTACH: 4, REACT: 8, MENTION_EVERYONE: 16, MANAGE_MESSAGES: 32,
  MANAGE_CHANNELS: 64, MANAGE_ROLES: 128, MANAGE_SERVER: 256, KICK: 512, BAN: 1024,
  INVITE: 2048, CHANGE_NICK: 4096, MANAGE_NICKS: 8192, CONNECT: 16384, SPEAK: 32768,
  STREAM: 65536, TIMEOUT: 131072, MANAGE_BOTS: 262144, ADMIN: 1073741824,
} as const;
export const ALL = Object.values(P).reduce((a, b) => a | b, 0);
/** What @everyone can do in a new server. */
export const DEFAULT_EVERYONE = P.VIEW | P.SEND | P.ATTACH | P.REACT | P.INVITE | P.CHANGE_NICK | P.CONNECT | P.SPEAK | P.STREAM;

const LABEL: Record<number, string> = {
  [P.VIEW]: "see this", [P.SEND]: "send messages", [P.ATTACH]: "attach files", [P.REACT]: "add reactions",
  [P.MENTION_EVERYONE]: "mention @everyone", [P.MANAGE_MESSAGES]: "manage messages", [P.MANAGE_CHANNELS]: "manage channels",
  [P.MANAGE_ROLES]: "manage roles", [P.MANAGE_SERVER]: "manage the server", [P.KICK]: "kick members", [P.BAN]: "ban members",
  [P.INVITE]: "create invites", [P.CHANGE_NICK]: "change your nickname", [P.MANAGE_NICKS]: "change nicknames",
  [P.CONNECT]: "join voice", [P.SPEAK]: "speak", [P.STREAM]: "share your screen", [P.TIMEOUT]: "time out members",
  [P.MANAGE_BOTS]: "add bots", [P.ADMIN]: "do that",
};

export type RoleRow = {
  id: string; server_id: string; name: string; color: string | null; icon: string | null; position: number;
  permissions: number; hoist: boolean; mentionable: boolean; is_default: boolean;
};
export type Override = { channel_id: string; target_type: "role" | "member"; target_id: string; allow: number; deny: number };
export type ChannelRow = { id: string; server_id: string | null; parent_id: string | null; kind: string };

/** Everything needed to decide what one member may do in one server. */
export type ServerCtx = {
  server: Record<string, any>;
  uuid: string;
  owner: boolean;
  roles: RoleRow[];                 // all of the server's roles, highest first
  myRoles: string[];                // role ids this member has (not @everyone)
  base: number;                     // server-wide permissions
  top: number;                      // position of their highest role (owner: Infinity)
  nickname: string | null;
  timeoutUntil: string | null;
};

const num = (v: unknown) => Number(v ?? 0);

/** Load a member's server context; 404 if they aren't in the server. */
export async function serverCtx(serverId: string, uuid: string): Promise<ServerCtx> {
  const [{ data: server }, { data: member }, { data: roles }, { data: mine }] = await Promise.all([
    db().from("servers").select("*").eq("id", serverId).maybeSingle(),
    db().from("server_members").select("*").eq("server_id", serverId).eq("uuid", uuid).maybeSingle(),
    db().from("server_roles").select("*").eq("server_id", serverId).order("position", { ascending: false }),
    db().from("server_member_roles").select("role_id").eq("server_id", serverId).eq("uuid", uuid),
  ]);
  if (!server || !member) throw new ApiError(404, "Server not found");
  const all = (roles ?? []).map((r) => ({ ...r, permissions: num(r.permissions) })) as RoleRow[];
  const myRoles = (mine ?? []).map((r) => r.role_id as string);
  const owner = server.owner === uuid;
  let base = all.find((r) => r.is_default)?.permissions ?? DEFAULT_EVERYONE;
  for (const r of all) if (myRoles.includes(r.id)) base |= r.permissions;
  if (member.role === "admin" && !all.some((r) => !r.is_default)) base |= P.ADMIN;   // servers from before roles
  if (owner || base & P.ADMIN) base = ALL;
  const top = owner ? Infinity : Math.max(0, ...all.filter((r) => myRoles.includes(r.id)).map((r) => r.position));
  return { server, uuid, owner, roles: all, myRoles, base, top, nickname: member.nickname ?? null, timeoutUntil: member.timeout_until ?? null };
}

export const has = (perms: number, p: number) => (perms & p) === p;

/** Permissions in one channel: server permissions, then the channel's overrides (or its category's). */
export function channelPerms(ctx: ServerCtx, channel: ChannelRow, overrides: Override[]): number {
  if (ctx.owner || has(ctx.base, P.ADMIN)) return ALL;
  let own = overrides.filter((o) => o.channel_id === channel.id);
  if (!own.length && channel.parent_id) own = overrides.filter((o) => o.channel_id === channel.parent_id);
  let perms = ctx.base;
  const everyone = ctx.roles.find((r) => r.is_default);
  const ev = own.find((o) => o.target_type === "role" && o.target_id === everyone?.id);
  if (ev) perms = (perms & ~num(ev.deny)) | num(ev.allow);
  let allow = 0, deny = 0;
  for (const o of own) {
    if (o.target_type === "role" && ctx.myRoles.includes(o.target_id)) { allow |= num(o.allow); deny |= num(o.deny); }
  }
  perms = (perms & ~deny) | allow;
  const me = own.find((o) => o.target_type === "member" && o.target_id === ctx.uuid);
  if (me) perms = (perms & ~num(me.deny)) | num(me.allow);
  // timed-out members can only read
  if (ctx.timeoutUntil && new Date(ctx.timeoutUntil) > new Date()) perms &= P.VIEW;
  // without "see this channel" nothing else in it works
  return has(perms, P.VIEW) ? perms : 0;
}

export async function overridesFor(channelIds: string[]): Promise<Override[]> {
  if (!channelIds.length) return [];
  const { data } = await db().from("channel_overrides").select("*").in("channel_id", channelIds);
  return (data ?? []).map((o) => ({ ...o, allow: num(o.allow), deny: num(o.deny) })) as Override[];
}

/** The member's permissions in one channel (with its category's overrides). */
export async function permsIn(ctx: ServerCtx, channel: ChannelRow): Promise<number> {
  return channelPerms(ctx, channel, await overridesFor([channel.id, ...(channel.parent_id ? [channel.parent_id] : [])]));
}

/** Throw 403 unless they have the permission (server-wide, or in a channel). */
export function need(perms: number, p: number, message?: string) {
  if (!has(perms, p)) throw new ApiError(403, message ?? `You don't have permission to ${LABEL[p] ?? "do that"}`);
}

export async function requirePerm(serverId: string, uuid: string, p: number): Promise<ServerCtx> {
  const ctx = await serverCtx(serverId, uuid);
  need(ctx.base, p);
  return ctx;
}

/** Highest role position of another member (owner: Infinity). */
export async function topOf(ctx: ServerCtx, uuid: string): Promise<number> {
  if (ctx.server.owner === uuid) return Infinity;
  const { data } = await db().from("server_member_roles").select("role_id").eq("server_id", ctx.server.id).eq("uuid", uuid);
  const ids = (data ?? []).map((r) => r.role_id);
  return Math.max(0, ...ctx.roles.filter((r) => ids.includes(r.id)).map((r) => r.position));
}

/** Like Discord: you can only act on members (and roles) below your own highest role. */
export async function requireAbove(ctx: ServerCtx, uuid: string) {
  if (uuid === ctx.uuid) return;
  if (ctx.owner) return;
  if ((await topOf(ctx, uuid)) >= ctx.top) throw new ApiError(403, "Their highest role is the same as or above yours");
}

export function requireRoleBelow(ctx: ServerCtx, role: RoleRow) {
  if (ctx.owner || role.is_default) return;
  if (role.position >= ctx.top) throw new ApiError(403, "You can only manage roles below your highest role");
}

/** "owner" / "admin" / "member" for older clients (Jace Launcher, the mod). */
export function legacyRole(ctx: ServerCtx): "owner" | "admin" | "member" {
  return ctx.owner ? "owner" : has(ctx.base, P.MANAGE_SERVER) ? "admin" : "member";
}

/** New servers get @everyone (and its permissions). */
export async function createDefaultRole(serverId: string) {
  await db().from("server_roles").insert({ server_id: serverId, name: "@everyone", position: 0, permissions: DEFAULT_EVERYONE, is_default: true });
}
