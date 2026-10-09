// GET: preview the server an invite is for (works signed out too, for invite pages)
// POST: join it. The code can be a server's invite code or its custom link (vanity).
import { ApiError, db, handler, me } from "@/lib/server";
import { MAX_SERVERS, announce, findInvite, memberRole } from "@/lib/chat";

export const GET = handler(async (req, ctx) => {
  const s = await findInvite((await ctx.params).code);
  const fresh = new Date(Date.now() - 3 * 60_000).toISOString();
  const [{ count }, { data: ids }] = await Promise.all([
    db().from("server_members").select("uuid", { count: "exact", head: true }).eq("server_id", s.id),
    db().from("server_members").select("uuid").eq("server_id", s.id).limit(1000),
  ]);
  const { count: online } = ids?.length
    ? await db().from("profiles").select("uuid", { count: "exact", head: true }).in("uuid", ids.map((m) => m.uuid)).gt("last_seen", fresh)
    : { count: 0 };
  let joined = false;
  if (req.headers.get("authorization")) {
    try { joined = Boolean(await memberRole(s.id, (await me(req)).uuid)); } catch { /* signed out */ }
  }
  return { server: { ...s, members: count ?? 0, online: online ?? 0 }, joined };
});

export const POST = handler(async (req, ctx) => {
  const p = await me(req);
  const s = await findInvite((await ctx.params).code);
  if (await memberRole(s.id, p.uuid)) return { server: s, already: true };
  if (s.invites_paused) throw new ApiError(403, "This server isn't taking new members right now");
  const { data: ban } = await db().from("server_bans").select("uuid").eq("server_id", s.id).eq("uuid", p.uuid).maybeSingle();
  if (ban) throw new ApiError(403, "You're banned from this server");
  const { count } = await db().from("server_members").select("uuid", { count: "exact", head: true }).eq("uuid", p.uuid);
  if ((count ?? 0) >= MAX_SERVERS) throw new ApiError(400, `You can be in up to ${MAX_SERVERS} servers`);
  const { data: full } = await db().from("servers").select("onboarding, rules").eq("id", s.id).single();
  const needsOnboarding = (full?.onboarding ?? []).length > 0 || Boolean(full?.rules);
  await db().from("server_members").insert({ server_id: s.id, uuid: p.uuid, role: "member", onboarded: !needsOnboarding });
  await announce(s.id, `${p.display_name || p.name} joined the server`);
  const { onMemberJoin } = await import("@/lib/bots");
  await onMemberJoin(s.id, p).catch((e: unknown) => console.error("bots", e));
  return { server: s, onboarding: needsOnboarding };
});
