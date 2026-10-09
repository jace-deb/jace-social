// POST (image body): set the server banner (manage server); DELETE: remove it
import { db, handler, me } from "@/lib/server";
import { cleanId, notifyChanged } from "@/lib/chat";
import { uploadImage } from "@/lib/images";
import { need, P, serverCtx } from "@/lib/perms";

async function done(id: string) {
  const { data: members } = await db().from("server_members").select("uuid").eq("server_id", id);
  await notifyChanged((members ?? []).map((m) => m.uuid), "servers", { kind: "changed", server_id: id });
}

export const POST = handler(async (req, ctx) => {
  const p = await me(req);
  const id = cleanId((await ctx.params).id);
  need((await serverCtx(id, p.uuid)).base, P.MANAGE_SERVER);
  const url = await uploadImage(req, `servers/${id}`);
  await db().from("servers").update({ banner_url: url }).eq("id", id);
  await done(id);
  return { banner_url: url };
});

export const DELETE = handler(async (req, ctx) => {
  const p = await me(req);
  const id = cleanId((await ctx.params).id);
  need((await serverCtx(id, p.uuid)).base, P.MANAGE_SERVER);
  await db().from("servers").update({ banner_url: null }).eq("id", id);
  await done(id);
  return { ok: true };
});
