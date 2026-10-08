// Group chats and servers. Both are made of "channels" with "channel_messages":
// a group chat is one channel with its own member list (server_id null), and a
// server has several channels that every member of the server can read.
import { randomBytes } from "node:crypto";
import { ApiError, db, inboxesOf, notify, publicProfile, type Profile } from "./server";

export const MAX_GROUP = 10;           // people in one group chat (like Discord's group DMs)
export const MAX_SERVERS = 100;        // servers one player can be in

export type Channel = {
  id: string; server_id: string | null; name: string; topic: string | null; icon_url: string | null;
  owner: string | null; position: number; created_at: string;
};
export type Role = "owner" | "admin" | "member";

export const inviteCode = () => randomBytes(6).toString("base64url");

export function cleanId(v: unknown): string {
  const s = String(v ?? "");
  if (!/^[0-9a-f-]{36}$/i.test(s)) throw new ApiError(400, "Bad id");
  return s.toLowerCase();
}

export function cleanName(v: unknown, what: string, max = 48): string {
  const s = String(v ?? "").trim();
  if (!s) throw new ApiError(400, `${what} can't be empty`);
  if (s.length > max) throw new ApiError(400, `${what} can be up to ${max} characters`);
  return s;
}

/** Text channel names look like Discord's: lowercase-with-dashes. */
export const channelName = (v: unknown) =>
  cleanName(v, "Channel name").toLowerCase().replace(/\s+/g, "-").replace(/[^\p{L}\p{N}_-]/gu, "").slice(0, 48) || "channel";

export async function memberRole(serverId: string, uuid: string): Promise<Role | null> {
  const { data } = await db().from("server_members").select("role").eq("server_id", serverId).eq("uuid", uuid).maybeSingle();
  return (data?.role as Role) ?? null;
}

export async function requireRole(serverId: string, uuid: string, need: "member" | "admin" | "owner"): Promise<Role> {
  const role = await memberRole(serverId, uuid);
  if (!role) throw new ApiError(404, "Server not found");
  const rank = { member: 0, admin: 1, owner: 2 };
  if (rank[role] < rank[need]) throw new ApiError(403, need === "owner" ? "Only the owner can do that" : "Only admins can do that");
  return role;
}

/** The channel, if this player may read it, plus everyone who can (to notify). */
export async function channelFor(channelId: string, uuid: string) {
  const { data } = await db().from("channels").select("*").eq("id", cleanId(channelId)).maybeSingle();
  if (!data) throw new ApiError(404, "Chat not found");
  const ch = data as Channel;
  const { data: rows } = ch.server_id
    ? await db().from("server_members").select("uuid").eq("server_id", ch.server_id)
    : await db().from("channel_members").select("uuid").eq("channel_id", ch.id);
  const members = (rows ?? []).map((r) => r.uuid as string);
  if (!members.includes(uuid)) throw new ApiError(404, "Chat not found");
  return { channel: ch, members };
}

/** Unread counts for channels (messages newer than what this player last read). */
export async function unreadCounts(uuid: string, channelIds: string[]): Promise<Record<string, number>> {
  if (!channelIds.length) return {};
  const { data: reads } = await db().from("channel_reads").select("channel_id, last_read")
    .eq("uuid", uuid).in("channel_id", channelIds);
  const last: Record<string, number> = {};
  for (const r of reads ?? []) last[r.channel_id] = Number(r.last_read);
  const out: Record<string, number> = {};
  await Promise.all(channelIds.map(async (id) => {
    const { count } = await db().from("channel_messages").select("id", { count: "exact", head: true })
      .eq("channel_id", id).gt("id", last[id] ?? 0).neq("sender", uuid);
    out[id] = count ?? 0;
  }));
  return out;
}

/** Profiles (public view) for a list of players, keyed by id. */
export async function profilesById(uuids: string[]) {
  if (!uuids.length) return {} as Record<string, ReturnType<typeof publicProfile>>;
  const { data } = await db().from("profiles").select("*").in("uuid", uuids);
  return Object.fromEntries((data ?? []).map((p) => [p.uuid, publicProfile(p as Profile)]));
}

/** Post a message (or a system line like "Alex joined") and tell everyone who can see it. */
export async function postMessage(channel: Channel, members: string[], sender: Profile | null, body: string,
                                  kind: "text" | "system" = "text") {
  const { data, error } = await db().from("channel_messages")
    .insert({ channel_id: channel.id, sender: sender?.uuid ?? null, body, kind })
    .select("id, channel_id, sender, body, kind, created_at, edited_at").single();
  if (error) throw error;
  if (sender) {
    await db().from("channel_reads").upsert({ channel_id: channel.id, uuid: sender.uuid, last_read: data.id });
  }
  await notify(await inboxesOf(members.filter((m) => m !== sender?.uuid)), "channel", {
    channel_id: channel.id, server_id: channel.server_id, id: data.id,
    from: sender?.uuid ?? null, name: sender ? (sender.display_name || sender.name) : null,
  });
  return data;
}

/** Tell players something about a group or server changed (members, names, channels). */
export async function notifyChanged(uuids: string[], event: "groups" | "servers", payload: Record<string, unknown>) {
  await notify(await inboxesOf(uuids), event, payload);
}
