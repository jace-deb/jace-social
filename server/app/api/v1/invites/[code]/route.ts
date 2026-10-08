// GET: preview the server an invite code is for
// POST: join it
import { ApiError, db, handler, me } from "@/lib/server";
import { MAX_SERVERS, memberRole, postMessage, type Channel } from "@/lib/chat";
import { notifyChanged } from "@/lib/chat";

async function find(code: string) {
  if (!/^[A-Za-z0-9_-]{4,32}$/.test(code)) throw new ApiError(404, "That invite doesn't work");
  const { data } = await db().from("servers").select("id, name, icon_url, description").eq("invite_code", code).maybeSingle();
  if (!data) throw new ApiError(404, "That invite doesn't work - ask for a new one");
  return data;
}

export const GET = handler(async (req, ctx) => {
  await me(req);
  const s = await find((await ctx.params).code);
  const { count } = await db().from("server_members").select("uuid", { count: "exact", head: true }).eq("server_id", s.id);
  return { server: { ...s, members: count ?? 0 } };
});

export const POST = handler(async (req, ctx) => {
  const p = await me(req);
  const s = await find((await ctx.params).code);
  if (await memberRole(s.id, p.uuid)) return { server: s, already: true };
  const { count } = await db().from("server_members").select("uuid", { count: "exact", head: true }).eq("uuid", p.uuid);
  if ((count ?? 0) >= MAX_SERVERS) throw new ApiError(400, `You can be in up to ${MAX_SERVERS} servers`);
  await db().from("server_members").insert({ server_id: s.id, uuid: p.uuid, role: "member" });
  const { data: ch } = await db().from("channels").select("*").eq("server_id", s.id).order("position").limit(1).maybeSingle();
  const { data: members } = await db().from("server_members").select("uuid").eq("server_id", s.id);
  const all = (members ?? []).map((m) => m.uuid);
  if (ch) await postMessage(ch as Channel, all, null, `${p.display_name || p.name} joined the server`, "system");
  await notifyChanged(all, "servers", { kind: "changed", server_id: s.id });
  return { server: s };
});
