// POST {name, server_id}: link Minecraft to the signed-in (Jace) player. Same proof as Minecraft
// sign-in (the app "joins" server_id from /auth/start with Mojang). If that Minecraft player
// already uses Jace Social, the two accounts become one, keeping friends and chats from both.
import { ApiError, body, db, ensureProfile, handler, me } from "@/lib/server";

export const POST = handler(async (req) => {
  const p = await me(req);
  const { name, server_id } = await body<{ name?: string; server_id?: string }>(req);
  if (!name || !server_id) throw new ApiError(400, "name and server_id are required");
  const { data: challenge } = await db().from("auth_challenges").delete().eq("server_id", server_id)
    .select("created_at").maybeSingle();
  if (!challenge || Date.now() - new Date(challenge.created_at).getTime() > 5 * 60_000)
    throw new ApiError(400, "Linking expired - try again");
  const r = await fetch("https://sessionserver.mojang.com/session/minecraft/hasJoined?" +
    new URLSearchParams({ username: name, serverId: server_id }));
  if (r.status !== 200) throw new ApiError(401, "Minecraft couldn't confirm this account");
  const mc = await r.json() as { id: string; name: string };

  if (p.uuid === mc.id) return { ok: true, uuid: mc.id };
  if (p.mc_linked !== false) throw new ApiError(409, "You already have a Minecraft account linked");
  const mcProfile = await ensureProfile(mc.id, mc.name, true);
  if (mcProfile.jace_sub && mcProfile.jace_sub !== p.jace_sub)
    throw new ApiError(409, "That Minecraft account is already linked to another Jace account");
  // the Jace-only account moves into the Minecraft one (its sessions too, so this token keeps working)
  const { error } = await db().rpc("merge_profiles", { src: p.uuid, dst: mc.id });
  if (error) throw error;
  return { ok: true, uuid: mc.id };
});
