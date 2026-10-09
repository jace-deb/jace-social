// GET: your bots
// POST {name}: make a bot (you're its owner) -> {bot, token}. The token is only shown now;
//      make a new one with POST /bots/{id}/token.
import { ApiError, body, createBotToken, db, handler, me, token, type Profile } from "@/lib/server";
import { ownBot } from "@/lib/bots";
import { cleanName } from "@/lib/chat";
import { randomBytes } from "node:crypto";

const MAX_BOTS = 10;

export const GET = handler(async (req) => {
  const p = await me(req);
  const { data } = await db().from("profiles").select("*").eq("bot_owner", p.uuid).eq("is_bot", true).order("signed_up");
  return { bots: (data ?? []).map((b) => ownBot(b as Profile)) };
});

export const POST = handler(async (req) => {
  const p = await me(req);
  if (p.is_bot) throw new ApiError(403, "Bots can't make bots");
  const name = cleanName((await body<{ name?: string }>(req)).name, "Bot name", 32);
  const { count } = await db().from("profiles").select("uuid", { count: "exact", head: true }).eq("bot_owner", p.uuid);
  if ((count ?? 0) >= MAX_BOTS) throw new ApiError(400, `You can have up to ${MAX_BOTS} bots`);
  const { data, error } = await db().from("profiles").insert({
    uuid: randomBytes(16).toString("hex"), name, inbox: token(), is_bot: true, bot_owner: p.uuid, mc_linked: false,
    signed_up: new Date().toISOString(),
  }).select("*").single();
  if (error) throw error;
  return { bot: ownBot(data as Profile), token: await createBotToken(data.uuid) };
});
