// GET ?q=name : find Jace Social players by display name, Jace or Minecraft username (10 max)
import { ApiError, db, handler, me, publicProfile, type Profile } from "@/lib/server";

export const GET = handler(async (req) => {
  await me(req);
  const q = (new URL(req.url).searchParams.get("q") ?? "").trim().replace(/^@/, "");
  if (q.length < 2) throw new ApiError(400, "Type at least 2 characters");
  const like = `${q.replace(/[%_\\,()]/g, "")}%`;
  const { data, error } = await db().from("profiles").select("*").not("signed_up", "is", null)
    .or(`name.ilike.${like},jace_name.ilike.${like},display_name.ilike.${like}`).limit(10);
  if (error) throw error;
  return { users: (data ?? []).map((p) => publicProfile(p as Profile)) };
});
