// PUT {program, blocks?}: save your bot's block program (see lib/blocks.ts). blocks is the
//     editor's own save of the workspace, kept so it can be opened again.
import { ApiError, body, db, handler, me } from "@/lib/server";
import { myBot } from "@/lib/bots";
import { validProgram } from "@/lib/blocks";

export const PUT = handler(async (req, ctx) => {
  const p = await me(req);
  const bot = await myBot(p, (await ctx.params).id);
  const b = await body<{ program?: unknown; blocks?: unknown }>(req);
  let program;
  try { program = b.program === null ? null : validProgram(b.program); } catch (e) { throw new ApiError(400, (e as Error).message); }
  if (b.blocks !== undefined && JSON.stringify(b.blocks).length > 400_000) throw new ApiError(400, "This bot is too big");
  await db().from("profiles").update({ bot_program: program ? { ...program, blocks: b.blocks ?? null } : null }).eq("uuid", bot.uuid);
  return { ok: true };
});
