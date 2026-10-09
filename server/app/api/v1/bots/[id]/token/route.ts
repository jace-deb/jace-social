// POST: a new API token for your bot (the old ones, and switched-in sessions, stop working)
import { createBotToken, db, handler, me } from "@/lib/server";
import { myBot } from "@/lib/bots";

export const POST = handler(async (req, ctx) => {
  const p = await me(req);
  const bot = await myBot(p, (await ctx.params).id);
  await db().from("sessions").delete().eq("uuid", bot.uuid);
  return { token: await createBotToken(bot.uuid) };
});
