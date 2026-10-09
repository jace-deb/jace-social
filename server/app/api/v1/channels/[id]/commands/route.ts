// GET: slash commands you can use here (from the bots in this chat)
// POST {bot, name, args}: use one
import { ApiError, body, handler, me } from "@/lib/server";
import { channelFor } from "@/lib/chat";
import { commandsIn, runCommand } from "@/lib/bots";
import { has, P } from "@/lib/perms";

export const maxDuration = 30;

export const GET = handler(async (req, ctx) => {
  const p = await me(req);
  const { members } = await channelFor((await ctx.params).id, p.uuid);
  return { commands: await commandsIn(members) };
});

export const POST = handler(async (req, ctx) => {
  const p = await me(req);
  const { channel, members, perms } = await channelFor((await ctx.params).id, p.uuid);
  if (!has(perms, P.SEND)) throw new ApiError(403, "You can't use commands in this channel");
  const b = await body<{ bot?: string; name?: string; args?: Record<string, unknown> }>(req);
  const args = Object.fromEntries(Object.entries(b.args ?? {}).slice(0, 10).map(([k, v]) => [String(k).slice(0, 32), String(v ?? "").slice(0, 500)]));
  try {
    await runCommand({ channel, members, user: p }, String(b.bot), String(b.name), args);
  } catch (e) {
    throw new ApiError(400, (e as Error).message);
  }
  return { ok: true };
});
