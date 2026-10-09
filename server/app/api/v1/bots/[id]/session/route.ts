// POST: sign in as your bot (for the account switcher) -> {token, uuid, name}
import { createSession, handler, me } from "@/lib/server";
import { myBot } from "@/lib/bots";

export const POST = handler(async (req, ctx) => {
  const p = await me(req);
  const bot = await myBot(p, (await ctx.params).id);
  const s = await createSession(bot.uuid);
  return { ...s, uuid: bot.uuid, name: bot.name, avatar_url: bot.avatar_url ?? null };
});
