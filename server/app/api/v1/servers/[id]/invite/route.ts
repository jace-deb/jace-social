// POST: make a new invite code (create invites). The old code stops working.
import { db, handler, me } from "@/lib/server";
import { cleanId, inviteCode } from "@/lib/chat";
import { need, P, serverCtx } from "@/lib/perms";

export const POST = handler(async (req, ctx) => {
  const p = await me(req);
  const id = cleanId((await ctx.params).id);
  need((await serverCtx(id, p.uuid)).base, P.INVITE);
  const code = inviteCode();
  await db().from("servers").update({ invite_code: code }).eq("id", id);
  return { invite_code: code };
});
