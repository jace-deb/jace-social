// GET ?state=&key=  -> {pending: true} until the browser part finishes, then the result once.
import { ApiError, db, handler, sha256 } from "@/lib/server";

export const GET = handler(async (req) => {
  const sp = new URL(req.url).searchParams;
  const { data } = await db().from("oauth_requests").select("poll_hash, result")
    .eq("state", sp.get("state") ?? "").maybeSingle();
  if (!data || !data.poll_hash || data.poll_hash !== sha256(sp.get("key") ?? "")) throw new ApiError(404, "Sign-in expired - try again");
  if (!data.result) return { pending: true };
  await db().from("oauth_requests").delete().eq("state", sp.get("state"));
  if (data.result.error) throw new ApiError(400, data.result.error);
  return data.result;
});
