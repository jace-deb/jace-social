// POST: make a new invite code (admins). The old code stops working.
import { db, handler, me } from "@/lib/server";
import { cleanId, inviteCode, requireRole } from "@/lib/chat";

export const POST = handler(async (req, ctx) => {
  const p = await me(req);
  const id = cleanId((await ctx.params).id);
  await requireRole(id, p.uuid, "admin");
  const code = inviteCode();
  await db().from("servers").update({ invite_code: code }).eq("id", id);
  return { invite_code: code };
});
