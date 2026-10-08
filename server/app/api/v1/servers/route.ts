// GET: servers you're in (with unread counts)
// POST {name}: create a server (you're the owner; it starts with #general)
import { ApiError, body, db, handler, me } from "@/lib/server";
import { MAX_SERVERS, cleanName, inviteCode, postMessage, unreadCounts, type Channel } from "@/lib/chat";

export const GET = handler(async (req) => {
  const p = await me(req);
  const { data: mine } = await db().from("server_members").select("role, joined_at, servers(*)").eq("uuid", p.uuid)
    .order("joined_at");
  const ids = (mine ?? []).map((m) => (m.servers as unknown as { id: string }).id);
  const { data: chans } = ids.length ? await db().from("channels").select("id, server_id").in("server_id", ids) : { data: [] };
  const unread = await unreadCounts(p.uuid, (chans ?? []).map((c) => c.id));
  return {
    servers: (mine ?? []).map((m) => {
      const s = m.servers as unknown as { id: string; name: string; icon_url: string | null; owner: string };
      const total = (chans ?? []).filter((c) => c.server_id === s.id).reduce((n, c) => n + (unread[c.id] ?? 0), 0);
      return { id: s.id, name: s.name, icon_url: s.icon_url, role: m.role, unread: total };
    }),
  };
});

export const POST = handler(async (req) => {
  const p = await me(req);
  const name = cleanName((await body<{ name?: string }>(req)).name, "Server name");
  const { count } = await db().from("server_members").select("uuid", { count: "exact", head: true }).eq("uuid", p.uuid);
  if ((count ?? 0) >= MAX_SERVERS) throw new ApiError(400, `You can be in up to ${MAX_SERVERS} servers`);
  const { data: s, error } = await db().from("servers").insert({ name, owner: p.uuid, invite_code: inviteCode() }).select("*").single();
  if (error) throw error;
  await db().from("server_members").insert({ server_id: s.id, uuid: p.uuid, role: "owner" });
  const { data: ch } = await db().from("channels").insert({ server_id: s.id, name: "general", position: 0 }).select("*").single();
  await postMessage(ch as Channel, [p.uuid], null, `Welcome to ${name}! Invite friends with the code ${s.invite_code}`, "system");
  return { server: s };
});
