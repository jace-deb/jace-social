"use client";
// Settings -> My bots: make bots, get their token, switch to them, and build them with
// blocks (the Scratch-style editor below).
import { useEffect, useRef, useState } from "react";
import { api, type Person } from "@/lib/client";
import { rememberAccount, switchAccount } from "@/lib/accounts";
import { compile, darkTheme, defineBlocks, STARTER, TOOLBOX } from "@/lib/botblocks";
import { Avatar } from "./ui";

type Bot = Person & { bot_public: boolean; bot_enabled: boolean; has_program: boolean };
type Err = (m: string) => void;

export function MyBots({ onError }: { onError: Err }) {
  const [bots, setBots] = useState<Bot[] | null>(null);
  const [name, setName] = useState("");
  const [token, setToken] = useState<{ bot: string; token: string } | null>(null);
  const [editing, setEditing] = useState<Bot | null>(null);
  const load = () => api<{ bots: Bot[] }>("/bots").then((d) => setBots(d.bots)).catch((e) => onError(e.message));
  useEffect(() => { void load(); }, []);   // eslint-disable-line react-hooks/exhaustive-deps

  async function create() {
    try {
      const r = await api<{ bot: Bot; token: string }>("/bots", { body: { name } });
      setName(""); setToken({ bot: r.bot.uuid, token: r.token }); await load();
    } catch (e) { onError((e as Error).message); }
  }
  async function patch(b: Bot, body: Record<string, unknown>) {
    try { await api(`/bots/${b.uuid}`, { method: "PATCH", body }); await load(); } catch (e) { onError((e as Error).message); }
  }

  if (editing) return <BotEditor bot={editing} onClose={() => { setEditing(null); void load(); }} onError={onError} />;
  return <>
    <h2>My bots</h2>
    <p className="muted small" style={{ margin: 0 }}>
      Bots are accounts you own. Build one with blocks (no code), or program it with its token and the Jace Social API.
      You can also switch to a bot and chat as it.</p>
    <form style={{ display: "flex", gap: 8 }} onSubmit={(e) => { e.preventDefault(); if (name.trim()) void create(); }}>
      <input placeholder="New bot's name" value={name} maxLength={32} onChange={(e) => setName(e.target.value)} />
      <button className="btn primary" disabled={!name.trim()}>Create bot</button>
    </form>
    {token && (
      <div className="card" style={{ borderLeft: "4px solid var(--yellow)" }}>
        <b>Bot token</b>
        <p className="muted small" style={{ margin: "4px 0" }}>Copy it now - it's only shown once. Anyone with it can act as your bot, so keep it secret.</p>
        <div style={{ display: "flex", gap: 8 }}>
          <input readOnly value={token.token} onFocus={(e) => e.target.select()} style={{ fontFamily: "ui-monospace, monospace" }} />
          <button className="btn" onClick={() => navigator.clipboard?.writeText(token.token)}>Copy</button>
          <button className="btn" onClick={() => setToken(null)}>Done</button>
        </div>
      </div>
    )}
    {bots === null ? <p className="muted">Loading…</p> : bots.length === 0 ? <p className="muted">No bots yet.</p> : bots.map((b) => (
      <div key={b.uuid} className="card bot-card">
        <div style={{ display: "flex", gap: 12, alignItems: "center" }}>
          <label title="Change picture" style={{ cursor: "pointer" }}>
            <Avatar p={b} size={48} />
            <input type="file" hidden accept="image/png,image/jpeg,image/gif,image/webp" onChange={async (e) => {
              const f = e.target.files?.[0];
              if (f) { try { await api(`/bots/${b.uuid}/avatar`, { raw: f }); await load(); } catch (err) { onError((err as Error).message); } }
            }} />
          </label>
          <div className="grow">
            <b>{b.name}</b> <span className="bot-tag">BOT</span>
            <div className="muted small">{b.has_program ? "Made with blocks" : "No blocks yet"} · {b.bot_public ? "Anyone can add it" : "Only you can add it"}</div>
          </div>
        </div>
        <textarea rows={2} defaultValue={b.bio ?? ""} maxLength={300} placeholder="What does it do?" onBlur={(e) => { if (e.target.value !== (b.bio ?? "")) void patch(b, { bio: e.target.value }); }} />
        <div className="bot-buttons">
          <button className="btn small primary" onClick={() => setEditing(b)}>🧩 Edit blocks</button>
          <button className="btn small" onClick={async () => {
            try {
              const s = await api<{ token: string; uuid: string; name: string; avatar_url: string | null }>(`/bots/${b.uuid}/session`, { body: {} });
              rememberAccount({ uuid: s.uuid, name: s.name, avatar_url: s.avatar_url, is_bot: true }, s.token);
              if (confirm(`Switch to ${b.name} now? You can switch back from the account menu.`)) switchAccount(s.uuid);
            } catch (e) { onError((e as Error).message); }
          }}>Switch to this bot</button>
          <button className="btn small" onClick={async () => {
            if (!confirm("Make a new token? The old one stops working right away.")) return;
            try { const r = await api<{ token: string }>(`/bots/${b.uuid}/token`, { body: {} }); setToken({ bot: b.uuid, token: r.token }); } catch (e) { onError((e as Error).message); }
          }}>New token</button>
          <label className="check small"><input type="checkbox" checked={b.bot_public} onChange={(e) => void patch(b, { bot_public: e.target.checked })} /> Public</label>
          <label className="check small"><input type="checkbox" checked={b.bot_enabled} onChange={(e) => void patch(b, { bot_enabled: e.target.checked })} /> On</label>
          <button className="btn small danger" onClick={async () => {
            if (!confirm(`Delete ${b.name}? It leaves every server.`)) return;
            try { await api(`/bots/${b.uuid}`, { method: "DELETE" }); await load(); } catch (e) { onError((e as Error).message); }
          }}>Delete</button>
        </div>
      </div>
    ))}
    <details className="card">
      <summary><b>Programming a bot with code</b></summary>
      <div className="small" style={{ display: "grid", gap: 6, marginTop: 8 }}>
        <span>Send requests to <code className="inline-code">{typeof location !== "undefined" ? location.origin : ""}/api/v1</code> with the header
          <code className="inline-code">Authorization: Bearer &lt;bot token&gt;</code>. The bot uses the same API as the app: read and send messages in
          <code className="inline-code">/channels/&#123;id&#125;/messages</code>, react with <code className="inline-code">/reactions</code>, and so on.</span>
        <span>Live events (new messages, and <code className="inline-code">command</code> when someone uses one of the bot's slash commands)
          arrive on the bot's private Realtime channel: <code className="inline-code">GET /me</code> gives its name and key.</span>
      </div>
    </details>
  </>;
}

/** The block editor (Blockly with the Scratch-like "zelos" look). */
function BotEditor({ bot, onClose, onError }: { bot: Bot; onClose: () => void; onError: Err }) {
  const div = useRef<HTMLDivElement>(null);
  const ws = useRef<import("blockly/core").WorkspaceSvg | null>(null);
  const [status, setStatus] = useState("Loading the editor…");
  const [dirty, setDirty] = useState(false);

  useEffect(() => {
    let disposed = false;
    (async () => {
      const Blockly = await import("blockly/core");
      await import("blockly/blocks");                         // text and number blocks
      const en = await import("blockly/msg/en");
      Blockly.setLocale(en as unknown as Record<string, string>);
      defineBlocks(Blockly);
      if (disposed || !div.current) return;
      const w = Blockly.inject(div.current, {
        toolbox: TOOLBOX, renderer: "zelos", theme: darkTheme(Blockly), trashcan: true, sounds: false,
        zoom: { controls: true, wheel: true, startScale: 0.8, maxScale: 2, minScale: 0.4 },
        grid: { spacing: 24, length: 2, colour: "#2a2f38", snap: true }, move: { scrollbars: true, drag: true, wheel: false },
      });
      ws.current = w;
      try {
        const d = await api<{ program: { blocks?: unknown } | null }>(`/bots/${bot.uuid}`);
        Blockly.serialization.workspaces.load((d.program?.blocks ?? STARTER) as object, w);
      } catch (e) {
        onError((e as Error).message);
        Blockly.serialization.workspaces.load(STARTER, w);
      }
      w.addChangeListener((ev) => { if (!ev.isUiEvent) setDirty(true); });
      setDirty(false);
      setStatus("");
    })().catch((e) => setStatus(`The editor couldn't load: ${e.message}`));
    return () => { disposed = true; ws.current?.dispose(); ws.current = null; };
  }, [bot.uuid]);   // eslint-disable-line react-hooks/exhaustive-deps

  async function save() {
    const w = ws.current;
    if (!w) return;
    const Blockly = await import("blockly/core");
    const { program, error } = compile(w);
    if (error) { setStatus(`⚠ ${error}`); return; }
    try {
      await api(`/bots/${bot.uuid}/program`, { method: "PUT", body: { program, blocks: Blockly.serialization.workspaces.save(w) } });
      setDirty(false);
      const n = program!.handlers.length;
      setStatus(`Saved ✓ ${n} "when" block${n === 1 ? "" : "s"}. Add ${bot.name} to a server to try it.`);
    } catch (e) { setStatus(`⚠ ${(e as Error).message}`); }
  }

  return (
    <div className="bot-editor">
      <div className="bot-editor-bar">
        <button className="btn small" onClick={() => { if (!dirty || confirm("Leave without saving?")) onClose(); }}>← Back</button>
        <Avatar p={bot} size={28} /><b>{bot.name}</b>
        <span className="grow muted small">{status}</span>
        <button className="btn primary small" onClick={save}>{dirty ? "Save" : "Saved"}</button>
      </div>
      <div className="bot-editor-help muted small">
        Drag a yellow <b>when</b> block out, then snap actions under it. Only blocks under a <b>when</b> block run.
        Bots run on the Jace Social server: up to 5 messages and a few seconds per event.
      </div>
      <div ref={div} className="blockly-area" />
    </div>
  );
}
