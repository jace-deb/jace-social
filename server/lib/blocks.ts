// Runs bots made with blocks (the Scratch-like editor in the app). The editor saves the
// blocks as this small JSON program; it runs here, on the server, when something happens.
// Nothing in it can reach outside its server: it only reads and sends messages, gives roles
// and keeps its own variables, with limits so one bot can't flood a chat.
import { db } from "./server";

export type Expr =
  | { t: "text"; v: string } | { t: "num"; v: number } | { t: "bool"; v: boolean }
  | { t: "join"; parts: Expr[] } | { t: "var"; name: string } | { t: "saved"; name: string }
  | { t: "msg_text" } | { t: "author_name" } | { t: "author_mention" } | { t: "channel_name" } | { t: "server_name" }
  | { t: "member_count" } | { t: "arg"; name: string } | { t: "time" } | { t: "date" }
  | { t: "random"; min: Expr; max: Expr } | { t: "pick"; options: Expr[] }
  | { t: "cmp"; op: "=" | "!=" | "<" | ">" | "<=" | ">=" | "contains" | "starts" | "ends"; a: Expr; b: Expr }
  | { t: "and" | "or"; a: Expr; b: Expr } | { t: "not"; a: Expr }
  | { t: "math"; op: "+" | "-" | "*" | "/" | "%"; a: Expr; b: Expr }
  | { t: "upper" | "lower" | "length" | "round" | "trim"; a: Expr }
  | { t: "word"; n: Expr; a: Expr } | { t: "has_role"; role: Expr };

export type Stmt =
  | { t: "reply"; text: Expr } | { t: "send"; channel: Expr; text: Expr } | { t: "react"; emoji: Expr }
  | { t: "if"; cond: Expr; then: Stmt[]; else?: Stmt[] } | { t: "repeat"; times: Expr; body: Stmt[] }
  | { t: "set"; name: string; value: Expr } | { t: "change"; name: string; by: Expr }
  | { t: "save"; name: string; value: Expr } | { t: "add_saved"; name: string; by: Expr }
  | { t: "give_role"; role: Expr } | { t: "take_role"; role: Expr }
  | { t: "wait"; seconds: Expr } | { t: "delete_message" } | { t: "stop" };

export type Handler = {
  event: "message" | "command" | "join";
  match?: { kind: "any" | "contains" | "starts" | "equals"; text: string };   // message
  command?: { name: string; description?: string; options?: { name: string; description?: string; required?: boolean }[] };
  actions: Stmt[];
};
export type Program = { version: 1; handlers: Handler[] };

/** What a running handler can see and do (given by lib/bots.ts). */
export type Env = {
  text: string;                                    // the message, or the command's arguments as typed
  args: Record<string, string>;
  author: { uuid: string; name: string } | null;
  channel: { id: string; name: string };
  server: { id: string; name: string; members: number } | null;
  hasRole(roleName: string): Promise<boolean>;
  reply(text: string): Promise<void>;
  send(channelName: string, text: string): Promise<void>;
  react(emoji: string): Promise<void>;
  giveRole(roleName: string, give: boolean): Promise<void>;
  deleteMessage(): Promise<void>;
  scope: string;                                   // server id (or channel id) for saved variables
  bot: string;
};

// waits stay short: the bot runs inside the request that triggered it
const LIMITS = { steps: 500, messages: 5, wait: 3, waitTotal: 4, text: 2000, repeat: 50 };

class Stop extends Error {}

export class Runner {
  private steps = 0;
  private sent = 0;
  private waited = 0;
  private vars = new Map<string, string | number | boolean>();
  constructor(private env: Env) {}

  private tick() {
    if (++this.steps > LIMITS.steps) throw new Stop("too many steps");
  }

  async run(stmts: Stmt[]) {
    try {
      await this.block(stmts);
    } catch (e) {
      if (!(e instanceof Stop)) throw e;
    }
  }

  private async block(stmts: Stmt[]) {
    for (const s of stmts ?? []) await this.stmt(s);
  }

  private async message(fn: () => Promise<void>) {
    if (this.sent >= LIMITS.messages) return;
    this.sent++;
    await fn();
  }

  private async stmt(s: Stmt): Promise<void> {
    this.tick();
    switch (s.t) {
      case "reply": { const t = str(await this.expr(s.text)); if (t.trim()) await this.message(() => this.env.reply(t.slice(0, LIMITS.text))); return; }
      case "send": { const t = str(await this.expr(s.text)); const c = str(await this.expr(s.channel));
        if (t.trim()) await this.message(() => this.env.send(c, t.slice(0, LIMITS.text))); return; }
      case "react": return this.env.react(str(await this.expr(s.emoji)).slice(0, 32));
      case "if": return (await this.truthy(s.cond)) ? this.block(s.then) : this.block(s.else ?? []);
      case "repeat": {
        const n = Math.min(LIMITS.repeat, Math.max(0, Math.floor(num(await this.expr(s.times)))));
        for (let i = 0; i < n; i++) await this.block(s.body);
        return;
      }
      case "set": this.vars.set(s.name, await this.expr(s.value)); return;
      case "change": this.vars.set(s.name, num(this.vars.get(s.name) ?? 0) + num(await this.expr(s.by))); return;
      case "save": await saveVar(this.env, s.name, await this.expr(s.value)); return;
      case "add_saved": await saveVar(this.env, s.name, num(await loadVar(this.env, s.name) ?? 0) + num(await this.expr(s.by))); return;
      case "give_role": return this.env.giveRole(str(await this.expr(s.role)), true);
      case "take_role": return this.env.giveRole(str(await this.expr(s.role)), false);
      case "wait": {
        const sec = Math.min(LIMITS.wait, Math.max(0, num(await this.expr(s.seconds))));
        if (this.waited + sec > LIMITS.waitTotal) throw new Stop("waited too long");
        this.waited += sec;
        await new Promise((ok) => setTimeout(ok, sec * 1000));
        return;
      }
      case "delete_message": return this.env.deleteMessage();
      case "stop": throw new Stop("stopped");
    }
  }

  private async truthy(e: Expr) {
    const v = await this.expr(e);
    return typeof v === "string" ? v !== "" && v !== "false" : Boolean(v);
  }

  async expr(e: Expr | undefined): Promise<string | number | boolean> {
    if (!e) return "";
    this.tick();
    const env = this.env;
    switch (e.t) {
      case "text": return String(e.v ?? "");
      case "num": return Number(e.v) || 0;
      case "bool": return Boolean(e.v);
      case "join": { let out = ""; for (const p of e.parts ?? []) out += str(await this.expr(p)); return out.slice(0, 4000); }
      case "var": return this.vars.get(e.name) ?? "";
      case "saved": return (await loadVar(env, e.name)) ?? "";
      case "msg_text": return env.text;
      case "author_name": return env.author?.name ?? "";
      case "author_mention": return env.author ? `<@${env.author.uuid}>` : "";
      case "channel_name": return env.channel.name;
      case "server_name": return env.server?.name ?? "";
      case "member_count": return env.server?.members ?? 0;
      case "arg": return env.args[e.name] ?? "";
      case "time": return new Date().toUTCString().slice(17, 22) + " UTC";
      case "date": return new Date().toISOString().slice(0, 10);
      case "random": {
        const a = Math.floor(num(await this.expr(e.min))), b = Math.floor(num(await this.expr(e.max)));
        const lo = Math.min(a, b), hi = Math.max(a, b);
        return lo + Math.floor(Math.random() * (hi - lo + 1));
      }
      case "pick": return e.options?.length ? this.expr(e.options[Math.floor(Math.random() * e.options.length)]) : "";
      case "cmp": {
        const a = await this.expr(e.a), b = await this.expr(e.b);
        const sa = str(a).toLowerCase(), sb = str(b).toLowerCase();
        const both = isNum(a) && isNum(b);
        switch (e.op) {
          case "=": return both ? num(a) === num(b) : sa === sb;
          case "!=": return both ? num(a) !== num(b) : sa !== sb;
          case "<": return num(a) < num(b);
          case ">": return num(a) > num(b);
          case "<=": return num(a) <= num(b);
          case ">=": return num(a) >= num(b);
          case "contains": return sa.includes(sb);
          case "starts": return sa.startsWith(sb);
          case "ends": return sa.endsWith(sb);
        }
        return false;
      }
      case "and": return (await this.truthy(e.a)) && (await this.truthy(e.b));
      case "or": return (await this.truthy(e.a)) || (await this.truthy(e.b));
      case "not": return !(await this.truthy(e.a));
      case "math": {
        const a = num(await this.expr(e.a)), b = num(await this.expr(e.b));
        const r = e.op === "+" ? a + b : e.op === "-" ? a - b : e.op === "*" ? a * b : e.op === "/" ? (b ? a / b : 0) : b ? a % b : 0;
        return Number.isFinite(r) ? Math.round(r * 1e6) / 1e6 : 0;
      }
      case "upper": return str(await this.expr(e.a)).toUpperCase();
      case "lower": return str(await this.expr(e.a)).toLowerCase();
      case "length": return str(await this.expr(e.a)).length;
      case "trim": return str(await this.expr(e.a)).trim();
      case "round": return Math.round(num(await this.expr(e.a)));
      case "word": {
        const words = str(await this.expr(e.a)).trim().split(/\s+/);
        return words[Math.floor(num(await this.expr(e.n))) - 1] ?? "";
      }
      case "has_role": return env.hasRole(str(await this.expr(e.role)));
    }
    return "";
  }
}

const str = (v: unknown) => (typeof v === "number" ? String(Math.round(v * 1e6) / 1e6) : String(v ?? ""));
const num = (v: unknown) => (typeof v === "number" ? v : Number(String(v).trim()) || 0);
const isNum = (v: unknown) => typeof v === "number" || (String(v).trim() !== "" && !isNaN(Number(v)));

async function loadVar(env: Env, name: string) {
  const { data } = await db().from("bot_storage").select("value").eq("bot", env.bot).eq("scope", env.scope).eq("key", name.slice(0, 64)).maybeSingle();
  return data?.value as string | number | boolean | undefined;
}

async function saveVar(env: Env, name: string, value: unknown) {
  await db().from("bot_storage").upsert({ bot: env.bot, scope: env.scope, key: name.slice(0, 64), value });
}

/** Check a saved program's shape, so the editor can't save something that won't run. */
export function validProgram(p: unknown): Program {
  const prog = p as Program;
  if (!prog || prog.version !== 1 || !Array.isArray(prog.handlers)) throw new Error("Not a block program");
  if (prog.handlers.length > 50) throw new Error("Up to 50 'when' blocks");
  if (JSON.stringify(prog).length > 100_000) throw new Error("This bot is too big");
  for (const h of prog.handlers) {
    if (!["message", "command", "join"].includes(h.event)) throw new Error("Unknown event");
    if (h.event === "command" && !/^[a-z0-9_-]{1,32}$/.test(h.command?.name ?? "")) throw new Error("Command names use lowercase letters, numbers, - and _");
    if (!Array.isArray(h.actions)) throw new Error("Missing actions");
  }
  return prog;
}

/** The slash commands a block program defines (for the "/" menu). */
export function programCommands(p: Program | null | undefined) {
  return (p?.handlers ?? []).filter((h) => h.event === "command" && h.command).map((h) => ({
    name: h.command!.name, description: h.command!.description ?? "", options: h.command!.options ?? [],
  }));
}
