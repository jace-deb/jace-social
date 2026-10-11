// PUT {code}: save your bot's JavaScript (it runs here, see lib/codebots.ts); null removes it.
//   Checked first: it has to load, and its bot.command(...)s become its slash commands.
// POST {code, text?, command?}: try code without saving or doing anything: runs it for a
//   pretend message (or command) and says what it would do.
import { ApiError, body, db, handler, me } from "@/lib/server";
import { myBot } from "@/lib/bots";
import { logBot } from "@/lib/botlog";
import { checkCode, runCode } from "@/lib/codebots";

export const PUT = handler(async (req, ctx) => {
  const p = await me(req);
  const bot = await myBot(p, (await ctx.params).id);
  const b = await body<{ code?: string | null }>(req);
  if (b.code === null || b.code === "") {
    await db().from("profiles").update({ bot_code: null, bot_commands: [] }).eq("uuid", bot.uuid);
    await logBot(bot.uuid, "event", "Code removed by its owner");
    return { ok: true, commands: [] };
  }
  const code = String(b.code ?? "");
  const r = await checkCode(code);
  if (r.error) throw new ApiError(400, r.error);
  const { error } = await db().from("profiles").update({ bot_code: code, bot_commands: r.commands }).eq("uuid", bot.uuid);
  if (error) throw new ApiError(500, error.message.includes("bot_code") ? "Run supabase/005_code_bots.sql first" : error.message);
  await logBot(bot.uuid, "event", `New code saved by its owner (${r.commands.length} command${r.commands.length === 1 ? "" : "s"})`);
  return { ok: true, commands: r.commands };
});

export const POST = handler(async (req, ctx) => {
  const p = await me(req);
  const bot = await myBot(p, (await ctx.params).id);
  const b = await body<{ code?: string; text?: string; command?: { name: string; args?: Record<string, string> } }>(req);
  const code = String(b.code ?? "");
  if (code.length > 60_000) throw new ApiError(400, "The code is too long (up to 60,000 characters)");
  const cmd = b.command?.name ? { name: String(b.command.name), args: b.command.args ?? {} } : undefined;
  const r = await runCode(code, {
    type: cmd ? "command" : "message", text: String(b.text ?? "hi bot").slice(0, 2000), command: cmd,
    author: { id: p.uuid, name: p.display_name || p.name }, channel: { id: "test", name: "test" }, server: null,
  }, {});
  return { result: r, bot: bot.uuid };
});
