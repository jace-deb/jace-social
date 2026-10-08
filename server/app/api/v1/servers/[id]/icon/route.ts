// POST (image body): set the server icon (admins)
import { db, handler, me } from "@/lib/server";
import { cleanId, notifyChanged, requireRole } from "@/lib/chat";
import { uploadImage } from "@/lib/images";

export const POST = handler(async (req, ctx) => {
  const p = await me(req);
  const id = cleanId((await ctx.params).id);
  await requireRole(id, p.uuid, "admin");
  const url = await uploadImage(req, `servers/${id}`);
  await db().from("servers").update({ icon_url: url }).eq("id", id);
  const { data: members } = await db().from("server_members").select("uuid").eq("server_id", id);
  await notifyChanged((members ?? []).map((m) => m.uuid), "servers", { kind: "changed", server_id: id });
  return { icon_url: url };
});
