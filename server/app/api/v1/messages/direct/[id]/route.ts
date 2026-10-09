// PATCH {body}: edit your own direct message; DELETE: delete it
import { ApiError, body, db, handler, me, notify } from "@/lib/server";
import { previewLater } from "@/lib/embeds";

async function load(req: Request, idParam: string) {
  const p = await me(req);
  const { data: m } = await db().from("messages").select("*").eq("id", Number(idParam)).maybeSingle();
  if (!m || m.sender !== p.uuid) throw new ApiError(404, "Message not found");
  const { data: o } = await db().from("profiles").select("inbox").eq("uuid", m.recipient).single();
  return { p, m, inbox: o?.inbox as string | undefined };
}

export const PATCH = handler(async (req, ctx) => {
  const { p, m, inbox } = await load(req, (await ctx.params).id);
  const text = String((await body<{ body?: string }>(req)).body ?? "").trim();
  if ((!text && !(m.attachments ?? []).length) || text.length > 2000) throw new ApiError(400, "Messages need 1-2000 characters");
  await db().from("messages").update({ body: text, edited_at: new Date().toISOString(), embeds: [] }).eq("id", m.id);
  const tell = async () => notify([inbox, p.inbox].filter(Boolean) as string[], "dm_update", { from: p.uuid, with: m.recipient, id: m.id, edited: true });
  await tell();
  previewLater("messages", m.id, text, tell);
  return { ok: true };
});

export const DELETE = handler(async (req, ctx) => {
  const { p, m, inbox } = await load(req, (await ctx.params).id);
  await db().from("messages").delete().eq("id", m.id);
  await db().from("message_reactions").delete().eq("scope", "d").eq("message_id", m.id);
  if (inbox) await notify([inbox], "dm_update", { from: p.uuid, id: m.id, deleted: true });
  return { ok: true };
});
