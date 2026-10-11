// Bots written in JavaScript and run here, on Jace Social's server ("hosted code bots").
// Each event runs the bot's code in a fresh QuickJS sandbox (a separate JavaScript engine
// compiled to WebAssembly): it can't touch the server, the network, files or other bots.
// It gets the event and its saved values, and hands back what it wants to do (reply, send,
// react, give roles...) which lib/bots.ts then does with the bot's own permissions.
// Limits: 300 ms of work, 32 MB of memory, 5 messages, 100 saved values per server.
import { getQuickJS, shouldInterruptAfterDeadline } from "quickjs-emscripten";

export type CodeEvent = {
  type: "message" | "command" | "join";
  text: string;                                    // the message (or the command's arguments as typed)
  author: { id: string; name: string };            // who sent it / used the command / joined
  channel: { id: string; name: string };
  server: { id: string; name: string } | null;
  command?: { name: string; args: Record<string, string> };
};

export type CodeAction =
  | { t: "reply"; text: string } | { t: "send"; channel: string; text: string } | { t: "react"; emoji: string }
  | { t: "give_role" | "take_role"; role: string } | { t: "delete_message" };

export type CommandDef = { name: string; description: string; options: { name: string; description?: string; required?: boolean }[] };

export type CodeResult = {
  actions: CodeAction[];
  storage: Record<string, unknown>;                // values it changed (null = deleted)
  logs: string[];                                  // console.log output
  commands: CommandDef[];                          // the slash commands it defines
  error?: string;
};

export const LIMITS = { ms: 300, memory: 32 * 1024 * 1024, actions: 10, messages: 5, text: 2000, logs: 50, keys: 100, storageBytes: 32_000 };

// What every bot's code runs inside. `bot`, `storage` and `console` are all it gets.
const PRELUDE = String.raw`
"use strict";
const __h = {}, __actions = [], __logs = [], __commands = [], __changed = {};
const __store = JSON.parse(__storageJson);
const __s = (v) => typeof v === "string" ? v : (() => { try { return JSON.stringify(v); } catch (e) { return String(v); } })();
const __push = (a) => { if (__actions.length >= ${LIMITS.actions}) throw new Error("Too many actions for one event (up to ${LIMITS.actions})"); __actions.push(a); };
const __text = (t) => { const s = __s(t); if (!s.trim()) throw new Error("Can't send an empty message"); return s.slice(0, ${LIMITS.text}); };
const console = {};
for (const k of ["log", "info", "warn", "error"]) console[k] = (...a) => { if (__logs.length < ${LIMITS.logs}) __logs.push((k === "log" || k === "info" ? "" : k + ": ") + a.map(__s).join(" ").slice(0, 500)); };
const storage = {
  get: (k) => __store[String(k)],
  set: (k, v) => {
    k = String(k).slice(0, 64);
    if (v === undefined) return storage.delete(k);
    v = JSON.parse(JSON.stringify(v));
    if (!(k in __store) && Object.keys(__store).length >= ${LIMITS.keys}) throw new Error("Up to ${LIMITS.keys} saved values");
    if (JSON.stringify(Object.assign({}, __store, { [k]: v })).length > ${LIMITS.storageBytes}) throw new Error("Saved values are too big (up to 32 KB)");
    __store[k] = v; __changed[k] = v;
  },
  delete: (k) => { k = String(k); delete __store[k]; __changed[k] = null; },
  keys: () => Object.keys(__store),
};
const bot = {
  on(event, fn) {
    if (!["message", "join"].includes(event)) throw new Error('bot.on: use "message" or "join" (commands: bot.command)');
    if (typeof fn !== "function") throw new Error("bot.on needs a function");
    (__h[event] = __h[event] || []).push(fn);
  },
  command(name, description, options, fn) {
    if (typeof options === "function") { fn = options; options = []; }
    if (typeof description === "function") { fn = description; description = ""; options = []; }
    name = String(name).toLowerCase();
    if (!/^[a-z0-9_-]{1,32}$/.test(name)) throw new Error("Command names use lowercase letters, numbers, - and _ (up to 32)");
    if (typeof fn !== "function") throw new Error("bot.command needs a function");
    __commands.push({ name, description: String(description || "").slice(0, 100),
      options: (options || []).slice(0, 10).map((o) => typeof o === "string" ? { name: o } : { name: String(o.name), description: String(o.description || ""), required: !!o.required }) });
    __h["command:" + name] = fn;
  },
  send: (channel, text) => __push({ t: "send", channel: String(channel), text: __text(text) }),
};
`;

// Runs after the bot's code: hands the event to its handlers, then reports back.
const DISPATCH = String.raw`
(() => {
  if (typeof __eventJson === "string") {
    const e = JSON.parse(__eventJson);
    const ev = Object.assign({}, e, {
      reply: (text) => __push({ t: "reply", text: __text(text) }),
      react: (emoji) => __push({ t: "react", emoji: String(emoji).slice(0, 16) }),
      delete: () => __push({ t: "delete_message" }),
      giveRole: (role) => __push({ t: "give_role", role: String(role) }),
      takeRole: (role) => __push({ t: "take_role", role: String(role) }),
    });
    if (e.type === "command") { const f = __h["command:" + e.command.name]; if (f) f(ev, e.command.args); }
    else for (const f of (__h[e.type] || [])) f(ev);
  }
})();
`;

/** Run the bot's code for one event (or, with no event, just load it: syntax errors, commands). */
export async function runCode(code: string, event: CodeEvent | null, storage: Record<string, unknown> = {}): Promise<CodeResult> {
  const QuickJS = await getQuickJS();
  const rt = QuickJS.newRuntime();
  rt.setMemoryLimit(LIMITS.memory);
  rt.setMaxStackSize(1024 * 1024);
  rt.setInterruptHandler(shouldInterruptAfterDeadline(Date.now() + LIMITS.ms));
  const vm = rt.newContext();
  const out: CodeResult = { actions: [], storage: {}, logs: [], commands: [] };
  try {
    const setStr = (name: string, value: string) => { const h = vm.newString(value); vm.setProp(vm.global, name, h); h.dispose(); };
    setStr("__storageJson", JSON.stringify(storage));
    if (event) setStr("__eventJson", JSON.stringify(event));
    const step = (src: string, file: string) => {
      const r = vm.evalCode(src, file);
      if (r.error) {
        const e = vm.dump(r.error) as { name?: string; message?: string; stack?: string } | string;
        r.error.dispose();
        throw new BotError(typeof e === "string" ? e : e.message === "interrupted"
          ? `Took too long (more than ${LIMITS.ms} ms)` : `${e.name ?? "Error"}: ${e.message ?? ""}${lineOf(e.stack)}`);
      }
      r.value.dispose();
    };
    step(PRELUDE, "jace.js");
    step(code, "bot.js");
    step(DISPATCH, "jace.js");
    // run promise callbacks (async handlers)
    const jobs = rt.executePendingJobs(100);
    if (jobs.error) {
      const e = vm.dump(jobs.error) as { name?: string; message?: string; stack?: string };
      jobs.error.dispose();
      throw new BotError(`${e?.name ?? "Error"}: ${e?.message ?? ""}${lineOf(e?.stack)}`);
    }
    const res = vm.evalCode("JSON.stringify({ a: __actions, l: __logs, c: __commands, s: __changed })", "jace.js");
    const json = vm.dump(vm.unwrapResult(res)) as string;
    const r = JSON.parse(json) as { a: CodeAction[]; l: string[]; c: CommandDef[]; s: Record<string, unknown> };
    Object.assign(out, { actions: r.a, logs: r.l, commands: r.c, storage: r.s });
  } catch (e) {
    out.error = e instanceof BotError ? e.message : `Error: ${(e as Error).message}`;
    // keep what it logged before the error, if we can still read it
    try {
      const l = vm.evalCode("JSON.stringify(typeof __logs === 'undefined' ? [] : __logs)");
      if (l.error) l.error.dispose();
      else { out.logs = JSON.parse(vm.dump(l.value)); l.value.dispose(); }
    } catch { /* gone */ }
  } finally {
    vm.dispose();
    rt.dispose();
  }
  return out;
}

class BotError extends Error {}

/** " (line 3)" from a QuickJS stack, for the bot's own file only. */
function lineOf(stack?: string) {
  const m = stack?.match(/bot\.js:(\d+)/);
  return m ? ` (line ${m[1]})` : "";
}

/** Check code before saving: it must load, and say which commands it has. */
export async function checkCode(code: string): Promise<{ commands: CommandDef[]; error?: string }> {
  if (code.length > 60_000) return { commands: [], error: "The code is too long (up to 60,000 characters)" };
  const r = await runCode(code, null);
  return { commands: r.commands, error: r.error };
}
