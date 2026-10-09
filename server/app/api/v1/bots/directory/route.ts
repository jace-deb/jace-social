// GET ?q=: public bots anyone can add to their server (the official Jace bot first)
import { db, handler, me, publicProfile, type Profile } from "@/lib/server";
import { JACE_BOT, ensureJaceBot } from "@/lib/bots";

export const GET = handler(async (req) => {
  await me(req);
  await ensureJaceBot();
  const q = (new URL(req.url).searchParams.get("q") ?? "").trim();
  let query = db().from("profiles").select("*").eq("is_bot", true).eq("bot_public", true).limit(50);
  if (q) query = query.ilike("name", `%${q.replace(/[%_]/g, "")}%`);
  const { data } = await query;
  const bots = (data ?? []).map((b) => ({ ...publicProfile(b as Profile), official: b.uuid === JACE_BOT }));
  bots.sort((a, b) => Number(b.official) - Number(a.official) || a.name.localeCompare(b.name));
  return { bots };
});
