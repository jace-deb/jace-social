// POST {bot}: add a bot to the server (add bots). It must be public, or yours.
import { ApiError, body, cleanUuid, db, handler, me } from "@/lib/server";
import { announce, cleanId, memberRole } from "@/lib/chat";
import { JACE_BOT, ensureJaceBot } from "@/lib/bots";
import { need, P, serverCtx } from "@/lib/perms";

export const POST = handler(async (req, ctx) => {
  const p = await me(req);
  const id = cleanId((await ctx.params).id);
  need((await serverCtx(id, p.uuid)).base, P.MANAGE_BOTS);
  const botId = cleanUuid((await body<{ bot?: string }>(req)).bot);
  if (botId === JACE_BOT) await ensureJaceBot();
  const { data: bot } = await db().from("profiles").select("uuid, name, bot_public, bot_owner").eq("uuid", botId).eq("is_bot", true).maybeSingle();
  if (!bot || (!bot.bot_public && bot.bot_owner !== p.uuid)) throw new ApiError(404, "Bot not found");
  if (await memberRole(id, bot.uuid)) return { ok: true, already: true };
  await db().from("server_members").insert({ server_id: id, uuid: bot.uuid, role: "member", onboarded: true });
  await announce(id, `${p.display_name || p.name} added the bot ${bot.name}`);
  return { ok: true };
});
