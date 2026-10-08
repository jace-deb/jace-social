// POST {name, topic?}: add a text channel (admins)
import { ApiError, body, db, handler, me } from "@/lib/server";
import { channelName, cleanId, notifyChanged, requireRole } from "@/lib/chat";

export const POST = handler(async (req, ctx) => {
  const p = await me(req);
  const id = cleanId((await ctx.params).id);
  await requireRole(id, p.uuid, "admin");
  const b = await body<{ name?: string; topic?: string }>(req);
  const { count } = await db().from("channels").select("id", { count: "exact", head: true }).eq("server_id", id);
  if ((count ?? 0) >= 50) throw new ApiError(400, "A server can have up to 50 channels");
  const topic = (b.topic ?? "").trim().slice(0, 200) || null;
  const { data, error } = await db().from("channels")
    .insert({ server_id: id, name: channelName(b.name), topic, position: count ?? 0 }).select("*").single();
  if (error) throw error;
  const { data: members } = await db().from("server_members").select("uuid").eq("server_id", id);
  await notifyChanged((members ?? []).map((m) => m.uuid), "servers", { kind: "changed", server_id: id });
  return { channel: data };
});
