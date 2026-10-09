"use client";
// The blocks in the bot editor (Scratch-style, made with Blockly) and the compiler that
// turns a workspace into the small program the server runs (lib/blocks.ts).
import type * as BlocklyType from "blockly/core";
import type { Expr, Handler, Program, Stmt } from "./blocks";

type B = typeof BlocklyType;
type Block = BlocklyType.Block;

const C = { event: "#FFBF00", action: "#9966FF", control: "#FFAB19", op: "#59C059", sense: "#5CB1D6", data: "#FF8C1A" };

/** Block shapes, in Blockly's JSON format. */
const BLOCKS = [
  // events (hats)
  { type: "jace_when_message", message0: "when someone sends a message %1 %2", args0: [
      { type: "field_dropdown", name: "KIND", options: [["(any)", "any"], ["containing", "contains"], ["starting with", "starts"], ["exactly", "equals"]] },
      { type: "field_input", name: "TEXT", text: "hello" }],
    message1: "%1", args1: [{ type: "input_statement", name: "DO" }], colour: C.event, tooltip: "Runs when someone sends a message in a chat this bot is in" },
  { type: "jace_when_command", message0: "when someone uses / %1", args0: [{ type: "field_input", name: "NAME", text: "hello" }],
    message1: "described as %1", args1: [{ type: "field_input", name: "DESC", text: "Say hello" }],
    message2: "with options %1", args2: [{ type: "field_input", name: "OPTIONS", text: "" }],
    message3: "%1", args3: [{ type: "input_statement", name: "DO" }], colour: C.event,
    tooltip: "A slash command. Options: names separated by commas (put ! after a name to require it, like city!)" },
  { type: "jace_when_join", message0: "when someone joins the server", message1: "%1", args1: [{ type: "input_statement", name: "DO" }], colour: C.event },
  // actions
  { type: "jace_reply", message0: "reply %1", args0: [{ type: "input_value", name: "TEXT" }], previousStatement: null, nextStatement: null, colour: C.action },
  { type: "jace_send", message0: "send %1 in # %2", args0: [{ type: "input_value", name: "TEXT" }, { type: "field_input", name: "CHANNEL", text: "general" }],
    previousStatement: null, nextStatement: null, colour: C.action, inputsInline: true },
  { type: "jace_react", message0: "react with %1", args0: [{ type: "field_input", name: "EMOJI", text: "👍" }], previousStatement: null, nextStatement: null, colour: C.action },
  { type: "jace_give_role", message0: "%1 the role %2", args0: [{ type: "field_dropdown", name: "MODE", options: [["give them", "give"], ["take away", "take"]] },
      { type: "field_input", name: "ROLE", text: "Member" }], previousStatement: null, nextStatement: null, colour: C.action,
    tooltip: "The bot needs Manage roles, and the role must be below the bot's highest role" },
  { type: "jace_delete", message0: "delete their message", previousStatement: null, nextStatement: null, colour: C.action },
  // control
  { type: "jace_if", message0: "if %1 then", args0: [{ type: "input_value", name: "COND", check: "Boolean" }], message1: "%1", args1: [{ type: "input_statement", name: "THEN" }],
    previousStatement: null, nextStatement: null, colour: C.control },
  { type: "jace_if_else", message0: "if %1 then", args0: [{ type: "input_value", name: "COND", check: "Boolean" }], message1: "%1", args1: [{ type: "input_statement", name: "THEN" }],
    message2: "else", message3: "%1", args3: [{ type: "input_statement", name: "ELSE" }], previousStatement: null, nextStatement: null, colour: C.control },
  { type: "jace_repeat", message0: "repeat %1 times", args0: [{ type: "input_value", name: "TIMES" }], message1: "%1", args1: [{ type: "input_statement", name: "DO" }],
    previousStatement: null, nextStatement: null, colour: C.control },
  { type: "jace_wait", message0: "wait %1 seconds", args0: [{ type: "input_value", name: "SECONDS" }], previousStatement: null, nextStatement: null, colour: C.control, inputsInline: true },
  { type: "jace_stop", message0: "stop", previousStatement: null, colour: C.control },
  // operators
  { type: "jace_join", message0: "join %1 %2", args0: [{ type: "input_value", name: "A" }, { type: "input_value", name: "B" }], output: null, inputsInline: true, colour: C.op },
  { type: "jace_math", message0: "%1 %2 %3", args0: [{ type: "input_value", name: "A" },
      { type: "field_dropdown", name: "OP", options: [["+", "+"], ["-", "-"], ["×", "*"], ["÷", "/"], ["mod", "%"]] }, { type: "input_value", name: "B" }],
    output: "Number", inputsInline: true, colour: C.op },
  { type: "jace_random", message0: "pick random %1 to %2", args0: [{ type: "input_value", name: "A" }, { type: "input_value", name: "B" }], output: "Number", inputsInline: true, colour: C.op },
  { type: "jace_compare", message0: "%1 %2 %3", args0: [{ type: "input_value", name: "A" },
      { type: "field_dropdown", name: "OP", options: [["=", "="], ["≠", "!="], ["<", "<"], [">", ">"], ["≤", "<="], ["≥", ">="], ["contains", "contains"], ["starts with", "starts"], ["ends with", "ends"]] },
      { type: "input_value", name: "B" }], output: "Boolean", inputsInline: true, colour: C.op },
  { type: "jace_logic", message0: "%1 %2 %3", args0: [{ type: "input_value", name: "A", check: "Boolean" },
      { type: "field_dropdown", name: "OP", options: [["and", "and"], ["or", "or"]] }, { type: "input_value", name: "B", check: "Boolean" }], output: "Boolean", inputsInline: true, colour: C.op },
  { type: "jace_not", message0: "not %1", args0: [{ type: "input_value", name: "A", check: "Boolean" }], output: "Boolean", colour: C.op },
  { type: "jace_text_op", message0: "%1 %2", args0: [{ type: "field_dropdown", name: "OP", options: [["length of", "length"], ["UPPERCASE", "upper"], ["lowercase", "lower"], ["trimmed", "trim"], ["rounded", "round"]] },
      { type: "input_value", name: "A" }], output: null, inputsInline: true, colour: C.op },
  { type: "jace_word", message0: "word %1 of %2", args0: [{ type: "input_value", name: "N" }, { type: "input_value", name: "A" }], output: "String", inputsInline: true, colour: C.op },
  { type: "jace_pick", message0: "one of %1", args0: [{ type: "field_input", name: "LIST", text: "red, green, blue" }], output: "String", colour: C.op,
    tooltip: "Picks one of these at random (separate them with commas)" },
  // sensing
  { type: "jace_sense", message0: "%1", args0: [{ type: "field_dropdown", name: "WHAT", options: [["message", "msg_text"], ["their name", "author_name"],
      ["@mention them", "author_mention"], ["channel name", "channel_name"], ["server name", "server_name"], ["member count", "member_count"], ["time", "time"], ["date", "date"]] }],
    output: null, colour: C.sense },
  { type: "jace_arg", message0: "option %1", args0: [{ type: "field_input", name: "NAME", text: "name" }], output: "String", colour: C.sense, tooltip: "What they typed for a command option" },
  { type: "jace_has_role", message0: "they have the role %1 ?", args0: [{ type: "field_input", name: "ROLE", text: "Member" }], output: "Boolean", colour: C.sense },
  // variables (named; "saved" ones are kept per server)
  { type: "jace_set", message0: "set %1 to %2", args0: [{ type: "field_input", name: "VAR", text: "count" }, { type: "input_value", name: "VALUE" }],
    previousStatement: null, nextStatement: null, inputsInline: true, colour: C.data },
  { type: "jace_change", message0: "change %1 by %2", args0: [{ type: "field_input", name: "VAR", text: "count" }, { type: "input_value", name: "BY" }],
    previousStatement: null, nextStatement: null, inputsInline: true, colour: C.data },
  { type: "jace_var", message0: "%1", args0: [{ type: "field_input", name: "VAR", text: "count" }], output: null, colour: C.data },
  { type: "jace_save", message0: "save %1 as %2", args0: [{ type: "input_value", name: "VALUE" }, { type: "field_input", name: "VAR", text: "score" }],
    previousStatement: null, nextStatement: null, inputsInline: true, colour: "#D65CD6", tooltip: "Saved values stay between messages (one set per server)" },
  { type: "jace_add_saved", message0: "add %1 to saved %2", args0: [{ type: "input_value", name: "BY" }, { type: "field_input", name: "VAR", text: "score" }],
    previousStatement: null, nextStatement: null, inputsInline: true, colour: "#D65CD6" },
  { type: "jace_saved", message0: "saved %1", args0: [{ type: "field_input", name: "VAR", text: "score" }], output: null, colour: "#D65CD6" },
];

const text = (v: string) => ({ kind: "block", type: "text", fields: { TEXT: v } });
const num = (v: number) => ({ kind: "block", type: "math_number", fields: { NUM: v } });
const shadow = (b: { type: string; fields: Record<string, unknown> }) => ({ shadow: { type: b.type, fields: b.fields } });

export const TOOLBOX = {
  kind: "categoryToolbox",
  contents: [
    { kind: "category", name: "Events", colour: C.event, contents: [
      { kind: "block", type: "jace_when_command" }, { kind: "block", type: "jace_when_message" }, { kind: "block", type: "jace_when_join" }] },
    { kind: "category", name: "Actions", colour: C.action, contents: [
      { kind: "block", type: "jace_reply", inputs: { TEXT: shadow(text("Hello!")) } },
      { kind: "block", type: "jace_send", inputs: { TEXT: shadow(text("Hi everyone")) } },
      { kind: "block", type: "jace_react" }, { kind: "block", type: "jace_give_role" }, { kind: "block", type: "jace_delete" }] },
    { kind: "category", name: "Control", colour: C.control, contents: [
      { kind: "block", type: "jace_if" }, { kind: "block", type: "jace_if_else" },
      { kind: "block", type: "jace_repeat", inputs: { TIMES: shadow(num(3)) } },
      { kind: "block", type: "jace_wait", inputs: { SECONDS: shadow(num(1)) } }, { kind: "block", type: "jace_stop" }] },
    { kind: "category", name: "Operators", colour: C.op, contents: [
      { kind: "block", type: "jace_join", inputs: { A: shadow(text("Hello, ")), B: shadow(text("world")) } },
      { kind: "block", type: "jace_math", inputs: { A: shadow(num(1)), B: shadow(num(2)) } },
      { kind: "block", type: "jace_random", inputs: { A: shadow(num(1)), B: shadow(num(10)) } },
      { kind: "block", type: "jace_compare", inputs: { A: shadow(text("")), B: shadow(text("")) } },
      { kind: "block", type: "jace_logic" }, { kind: "block", type: "jace_not" },
      { kind: "block", type: "jace_text_op", inputs: { A: shadow(text("hello")) } },
      { kind: "block", type: "jace_word", inputs: { N: shadow(num(1)), A: shadow(text("one two three")) } },
      { kind: "block", type: "jace_pick" }, { kind: "block", type: "text" }, { kind: "block", type: "math_number" }] },
    { kind: "category", name: "Sensing", colour: C.sense, contents: [
      { kind: "block", type: "jace_sense" }, { kind: "block", type: "jace_sense", fields: { WHAT: "author_name" } },
      { kind: "block", type: "jace_arg" }, { kind: "block", type: "jace_has_role" }] },
    { kind: "category", name: "Variables", colour: C.data, contents: [
      { kind: "block", type: "jace_set", inputs: { VALUE: shadow(num(0)) } }, { kind: "block", type: "jace_change", inputs: { BY: shadow(num(1)) } },
      { kind: "block", type: "jace_var" },
      { kind: "block", type: "jace_save", inputs: { VALUE: shadow(num(0)) } }, { kind: "block", type: "jace_add_saved", inputs: { BY: shadow(num(1)) } },
      { kind: "block", type: "jace_saved" }] },
  ],
};

/** What a new bot starts with: /hello -> "Hello, <name>!" */
export const STARTER = {
  blocks: { languageVersion: 0, blocks: [{
    type: "jace_when_command", x: 40, y: 40, fields: { NAME: "hello", DESC: "Say hello", OPTIONS: "" },
    inputs: { DO: { block: { type: "jace_reply", inputs: { TEXT: { shadow: { type: "text", fields: { TEXT: "" } },
      block: { type: "jace_join", inputs: { A: { shadow: { type: "text", fields: { TEXT: "Hello, " } } },
        B: { shadow: { type: "text", fields: { TEXT: "" } }, block: { type: "jace_sense", fields: { WHAT: "author_name" } } } } } } } } } },
  }] },
};

let defined = false;
export function defineBlocks(Blockly: B) {
  if (defined) return;
  Blockly.common.defineBlocksWithJsonArray(BLOCKS);
  defined = true;
}

export function darkTheme(Blockly: B) {
  return Blockly.Theme.defineTheme("jace-dark", {
    name: "jace-dark",
    base: Blockly.Themes.Classic,
    componentStyles: {
      workspaceBackgroundColour: "#111317", toolboxBackgroundColour: "#16181d", toolboxForegroundColour: "#e6e8eb",
      flyoutBackgroundColour: "#1c1f26", flyoutForegroundColour: "#e6e8eb", flyoutOpacity: 1, scrollbarColour: "#3a404b",
      insertionMarkerColour: "#ffffff", insertionMarkerOpacity: 0.3, cursorColour: "#d0d0d0",
    },
    fontStyle: { family: "Inter, system-ui, sans-serif", weight: "600", size: 12 },
  });
}

// -------------------------------------------------------------------- compiler

class CompileError extends Error {}

function chain(first: Block | null): Stmt[] {
  const out: Stmt[] = [];
  for (let b = first; b; b = b.getNextBlock()) {
    if (b.isEnabled()) out.push(stmt(b));
  }
  return out;
}

function value(b: Block, input: string, fallback: Expr = { t: "text", v: "" }): Expr {
  const t = b.getInputTargetBlock(input);
  return t ? expr(t) : fallback;
}

function field(b: Block, name: string) {
  return String(b.getFieldValue(name) ?? "");
}

function stmt(b: Block): Stmt {
  switch (b.type) {
    case "jace_reply": return { t: "reply", text: value(b, "TEXT") };
    case "jace_send": return { t: "send", text: value(b, "TEXT"), channel: { t: "text", v: field(b, "CHANNEL") } };
    case "jace_react": return { t: "react", emoji: { t: "text", v: field(b, "EMOJI") } };
    case "jace_give_role": return { t: field(b, "MODE") === "take" ? "take_role" : "give_role", role: { t: "text", v: field(b, "ROLE") } };
    case "jace_delete": return { t: "delete_message" };
    case "jace_if": return { t: "if", cond: value(b, "COND", { t: "bool", v: false }), then: chain(b.getInputTargetBlock("THEN")) };
    case "jace_if_else": return { t: "if", cond: value(b, "COND", { t: "bool", v: false }), then: chain(b.getInputTargetBlock("THEN")), else: chain(b.getInputTargetBlock("ELSE")) };
    case "jace_repeat": return { t: "repeat", times: value(b, "TIMES", { t: "num", v: 1 }), body: chain(b.getInputTargetBlock("DO")) };
    case "jace_wait": return { t: "wait", seconds: value(b, "SECONDS", { t: "num", v: 1 }) };
    case "jace_stop": return { t: "stop" };
    case "jace_set": return { t: "set", name: field(b, "VAR"), value: value(b, "VALUE") };
    case "jace_change": return { t: "change", name: field(b, "VAR"), by: value(b, "BY", { t: "num", v: 1 }) };
    case "jace_save": return { t: "save", name: field(b, "VAR"), value: value(b, "VALUE") };
    case "jace_add_saved": return { t: "add_saved", name: field(b, "VAR"), by: value(b, "BY", { t: "num", v: 1 }) };
  }
  throw new CompileError(`"${b.type}" can't be used as a step`);
}

function expr(b: Block): Expr {
  switch (b.type) {
    case "text": return { t: "text", v: field(b, "TEXT") };
    case "math_number": return { t: "num", v: Number(field(b, "NUM")) || 0 };
    case "jace_join": return { t: "join", parts: [value(b, "A"), value(b, "B")] };
    case "jace_math": return { t: "math", op: field(b, "OP") as "+", a: value(b, "A", { t: "num", v: 0 }), b: value(b, "B", { t: "num", v: 0 }) };
    case "jace_random": return { t: "random", min: value(b, "A", { t: "num", v: 1 }), max: value(b, "B", { t: "num", v: 10 }) };
    case "jace_compare": return { t: "cmp", op: field(b, "OP") as "=", a: value(b, "A"), b: value(b, "B") };
    case "jace_logic": return { t: field(b, "OP") as "and", a: value(b, "A", { t: "bool", v: false }), b: value(b, "B", { t: "bool", v: false }) };
    case "jace_not": return { t: "not", a: value(b, "A", { t: "bool", v: false }) };
    case "jace_text_op": return { t: field(b, "OP") as "upper", a: value(b, "A") };
    case "jace_word": return { t: "word", n: value(b, "N", { t: "num", v: 1 }), a: value(b, "A") };
    case "jace_pick": return { t: "pick", options: field(b, "LIST").split(",").map((s) => ({ t: "text", v: s.trim() } as Expr)).filter((e) => (e as { v: string }).v) };
    case "jace_sense": return { t: field(b, "WHAT") } as Expr;
    case "jace_arg": return { t: "arg", name: field(b, "NAME").trim().toLowerCase() };
    case "jace_has_role": return { t: "has_role", role: { t: "text", v: field(b, "ROLE") } };
    case "jace_var": return { t: "var", name: field(b, "VAR") };
    case "jace_saved": return { t: "saved", name: field(b, "VAR") };
  }
  throw new CompileError(`"${b.type}" can't be used as a value`);
}

/** The workspace -> {program} for the server, or {error} saying what to fix. */
export function compile(ws: BlocklyType.Workspace): { program?: Program; error?: string } {
  try {
    const handlers: Handler[] = [];
    const names = new Set<string>();
    for (const top of ws.getTopBlocks(true)) {
      if (!top.isEnabled()) continue;
      if (top.type === "jace_when_message") {
        handlers.push({ event: "message", match: { kind: field(top, "KIND") as "any", text: field(top, "TEXT") }, actions: chain(top.getInputTargetBlock("DO")) });
      } else if (top.type === "jace_when_command") {
        const name = field(top, "NAME").trim().toLowerCase().replace(/^\//, "");
        if (!/^[a-z0-9_-]{1,32}$/.test(name)) throw new CompileError(`/${name} - command names use lowercase letters, numbers, - and _`);
        if (names.has(name)) throw new CompileError(`There are two /${name} commands`);
        names.add(name);
        const options = field(top, "OPTIONS").split(",").map((s) => s.trim()).filter(Boolean).slice(0, 10).map((s) => ({
          name: s.replace(/!$/, "").toLowerCase().replace(/[^a-z0-9_-]/g, "").slice(0, 32), required: s.endsWith("!"),
        })).filter((o) => o.name);
        handlers.push({ event: "command", command: { name, description: field(top, "DESC").slice(0, 100), options }, actions: chain(top.getInputTargetBlock("DO")) });
      } else if (top.type === "jace_when_join") {
        handlers.push({ event: "join", actions: chain(top.getInputTargetBlock("DO")) });
      }
      // loose blocks that aren't under a "when" block don't run
    }
    return { program: { version: 1, handlers } };
  } catch (e) {
    return { error: (e as Error).message };
  }
}
