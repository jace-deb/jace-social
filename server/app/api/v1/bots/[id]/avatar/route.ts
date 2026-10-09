// POST (image body): set your bot's picture
import { db, handler, me } from "@/lib/server";
import { myBot } from "@/lib/bots";
import { uploadImage } from "@/lib/images";

export const POST = handler(async (req, ctx) => {
  const p = await me(req);
  const bot = await myBot(p, (await ctx.params).id);
  const url = await uploadImage(req, `bots/${bot.uuid}`);
  await db().from("profiles").update({ avatar_url: url }).eq("uuid", bot.uuid);
  return { avatar_url: url };
});
