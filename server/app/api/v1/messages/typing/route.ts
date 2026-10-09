// POST {to}: "I'm typing" in a direct message
import { areFriends, body, cleanUuid, db, handler, me, notify } from "@/lib/server";

export const POST = handler(async (req) => {
  const p = await me(req);
  const to = cleanUuid((await body<{ to?: string }>(req)).to);
  if (!(await areFriends(p.uuid, to))) return { ok: true };
  const { data: o } = await db().from("profiles").select("inbox").eq("uuid", to).single();
  if (o) await notify([o.inbox], "typing", { dm: true, from: p.uuid, name: p.display_name || p.name });
  return { ok: true };
});
