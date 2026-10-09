// POST {answers: [[optionIndex, ...], ...], agreed?: true}: finish the server's onboarding.
//      Each picked answer gives its roles; agreed = accepted the rules.
import { ApiError, body, db, handler, me } from "@/lib/server";
import { cleanId, notifyChanged } from "@/lib/chat";
import { serverCtx } from "@/lib/perms";

export const POST = handler(async (req, ctx) => {
  const p = await me(req);
  const id = cleanId((await ctx.params).id);
  const sc = await serverCtx(id, p.uuid);
  const b = await body<{ answers?: number[][]; agreed?: boolean }>(req);
  if (sc.server.rules && !b.agreed) throw new ApiError(400, "Read and agree to the rules first");
  const questions = (sc.server.onboarding ?? []) as { multiple?: boolean; options: { role_ids?: string[] }[] }[];
  const roleIds = new Set<string>();
  questions.forEach((q, i) => {
    const picks = (b.answers?.[i] ?? []).map(Number).filter((n) => n >= 0 && n < q.options.length);
    for (const n of q.multiple ? picks : picks.slice(0, 1)) for (const r of q.options[n].role_ids ?? []) roleIds.add(r);
  });
  const valid = sc.roles.filter((r) => !r.is_default && roleIds.has(r.id));
  if (valid.length) await db().from("server_member_roles").upsert(valid.map((r) => ({ server_id: id, uuid: p.uuid, role_id: r.id })));
  await db().from("server_members").update({ onboarded: true }).eq("server_id", id).eq("uuid", p.uuid);
  await notifyChanged([p.uuid], "servers", { kind: "changed", server_id: id });
  return { ok: true, roles: valid.map((r) => r.id) };
});
