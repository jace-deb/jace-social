// GET ?server=<id>&before=<log id>: everything a bot did (its activity log, newest first).
//   Its owner sees all of it; in a server, people who can manage that server see what it did there.
import { ApiError, db, handler, me } from "@/lib/server";
import { has, P, serverCtx } from "@/lib/perms";

export const GET = handler(async (req, ctx) => {
  const p = await me(req);
  const id = (await ctx.params).id;
  const url = new URL(req.url);
  const server = url.searchParams.get("server");
  const before = Number(url.searchParams.get("before")) || null;
  if (!/^[0-9a-f]{32}$/.test(id)) throw new ApiError(404, "Bot not found");
  const { data: bot } = await db().from("profiles").select("uuid, name, bot_owner").eq("uuid", id).eq("is_bot", true).maybeSingle();
  if (!bot) throw new ApiError(404, "Bot not found");
  const owner = bot.bot_owner === p.uuid;
  if (!owner) {
    if (!server) throw new ApiError(403, "Only its owner can see everything it does");
    const sc = await serverCtx(server, p.uuid).catch(() => null);
    if (!sc || !has(sc.base, P.MANAGE_SERVER)) throw new ApiError(403, "You need Manage Server to see what bots do here");
  }
  let q = db().from("bot_logs").select("id, at, server_id, channel_id, kind, detail").eq("bot", id).order("id", { ascending: false }).limit(100);
  if (server) q = q.eq("server_id", server);
  if (before) q = q.lt("id", before);
  const { data, error } = await q;
  if (error) throw new ApiError(500, error.message.includes("bot_logs") ? "Run supabase/005_code_bots.sql first" : error.message);
  // names for the channels and servers mentioned
  const chans = [...new Set((data ?? []).map((r) => r.channel_id).filter(Boolean))] as string[];
  const servs = [...new Set((data ?? []).map((r) => r.server_id).filter(Boolean))] as string[];
  const [{ data: cs }, { data: ss }] = await Promise.all([
    chans.length ? db().from("channels").select("id, name").in("id", chans) : Promise.resolve({ data: [] as { id: string; name: string }[] }),
    servs.length ? db().from("servers").select("id, name").in("id", servs) : Promise.resolve({ data: [] as { id: string; name: string }[] }),
  ]);
  return {
    entries: data ?? [], owner,
    channels: Object.fromEntries((cs ?? []).map((c) => [c.id, c.name])),
    servers: Object.fromEntries((ss ?? []).map((s) => [s.id, s.name])),
  };
});
