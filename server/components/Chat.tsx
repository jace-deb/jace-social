"use client";
// One conversation: a direct message, a group chat or a server channel.
import { useEffect, useLayoutEffect, useRef, useState, type FormEvent } from "react";
import { api, timeLabel, type Message, type Person } from "@/lib/client";
import { Avatar } from "./ui";

export type ChatTarget =
  | { kind: "dm"; uuid: string; name: string }
  | { kind: "channel"; id: string; name: string; isServer: boolean };

export function Chat({ target, me, people, canModerate, reloadKey, onOpenProfile, onError, placeholder }: {
  target: ChatTarget; me: Person; people: Record<string, Person>; canModerate: boolean; reloadKey: number;
  onOpenProfile: (uuid: string) => void; onError: (msg: string) => void; placeholder: string;
}) {
  const [msgs, setMsgs] = useState<Message[]>([]);
  const [senders, setSenders] = useState<Record<string, Person>>({});
  const [loading, setLoading] = useState(true);
  const [more, setMore] = useState(false);
  const [text, setText] = useState("");
  const [editing, setEditing] = useState<{ id: number; text: string } | null>(null);
  const box = useRef<HTMLDivElement>(null);
  const stick = useRef(true);
  const key = target.kind === "dm" ? `dm:${target.uuid}` : `ch:${target.id}`;

  async function load(before?: number) {
    try {
      if (target.kind === "dm") {
        const d = await api<{ messages: Message[] }>(`/messages?with=${target.uuid}${before ? `&before=${before}` : ""}`);
        setMsgs((old) => (before ? [...d.messages, ...old] : d.messages));
        setMore(d.messages.length === 50);
        if (!before) await api("/messages/read", { body: { with: target.uuid } }).catch(() => {});
      } else {
        const d = await api<{ messages: Message[]; people: Record<string, Person> }>(
          `/channels/${target.id}/messages${before ? `?before=${before}` : ""}`);
        setMsgs((old) => (before ? [...d.messages, ...old] : d.messages));
        setSenders((s) => ({ ...s, ...d.people }));
        setMore(d.messages.length === 50);
        if (!before) await api(`/channels/${target.id}/read`, { body: {} }).catch(() => {});
      }
    } catch (e) {
      onError((e as Error).message);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { setMsgs([]); setLoading(true); stick.current = true; void load(); }, [key]);   // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { if (reloadKey) void load(); }, [reloadKey]);                                   // eslint-disable-line react-hooks/exhaustive-deps
  useLayoutEffect(() => {
    if (stick.current && box.current) box.current.scrollTop = box.current.scrollHeight;
  }, [msgs]);

  async function send(e?: FormEvent) {
    e?.preventDefault();
    const body = text.trim();
    if (!body) return;
    setText("");
    stick.current = true;
    try {
      if (target.kind === "dm") await api("/messages", { body: { to: target.uuid, body } });
      else await api(`/channels/${target.id}/messages`, { body: { body } });
      await load();
    } catch (err) {
      setText(body);
      onError((err as Error).message);
    }
  }

  async function saveEdit() {
    if (!editing) return;
    try {
      await api(`/messages/${editing.id}`, { method: "PATCH", body: { body: editing.text } });
      setEditing(null);
      await load();
    } catch (e) { onError((e as Error).message); }
  }

  async function remove(id: number) {
    if (!confirm("Delete this message?")) return;
    try { await api(`/messages/${id}`, { method: "DELETE" }); await load(); } catch (e) { onError((e as Error).message); }
  }

  const who = (uuid: string | null): Person | undefined =>
    uuid ? (uuid === me.uuid ? me : people[uuid] ?? senders[uuid]) : undefined;

  return (
    <>
      <div className="messages" ref={box}
        onScroll={(e) => { const el = e.currentTarget; stick.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80; }}>
        {more && (
          <div style={{ textAlign: "center", padding: 8 }}>
            <button className="btn small" onClick={() => { stick.current = false; void load(msgs[0]?.id); }}>Load older messages</button>
          </div>
        )}
        {!loading && !msgs.length && (
          <div className="empty"><div><h3 style={{ margin: 0 }}>This is the start of {target.kind === "dm" ? `your chat with ${target.name}` : target.kind === "channel" && target.isServer ? `#${target.name}` : target.name}</h3>
            <p>Say hi!</p></div></div>
        )}
        {msgs.map((m, i) => {
          const prev = msgs[i - 1];
          const system = m.kind === "system";
          const first = system || !prev || prev.sender !== m.sender || prev.kind === "system" ||
            new Date(m.created_at).getTime() - new Date(prev.created_at).getTime() > 7 * 60_000;
          const p = who(m.sender);
          const mine = m.sender === me.uuid;
          return (
            <div key={m.id} className={`msg${first ? " first" : ""}${system ? " system" : ""}`}>
              <div className="av">{first && !system && <span onClick={() => m.sender && onOpenProfile(m.sender)} style={{ cursor: "pointer" }}><Avatar p={p ?? { name: "?" }} size={40} /></span>}
                {system && <span className="muted" style={{ paddingLeft: 12 }}>→</span>}</div>
              <div>
                {first && !system && (
                  <div className="meta"><b onClick={() => m.sender && onOpenProfile(m.sender)}>{p?.name ?? "Someone"}</b>
                    <span className="time">{timeLabel(m.created_at)}</span></div>
                )}
                {editing?.id === m.id ? (
                  <div style={{ display: "grid", gap: 6 }}>
                    <textarea rows={2} value={editing.text} onChange={(e) => setEditing({ id: m.id, text: e.target.value })}
                      onKeyDown={(e) => { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); void saveEdit(); } }} />
                    <div className="small muted">Enter to save · Esc to <button className="icon-btn small" onClick={() => setEditing(null)}>cancel</button></div>
                  </div>
                ) : (
                  <div className="body">{m.body}{m.edited_at && <span className="edited">(edited)</span>}</div>
                )}
              </div>
              {target.kind === "channel" && !system && (mine || canModerate) && editing?.id !== m.id && (
                <div className="tools">
                  {mine && <button className="icon-btn" title="Edit" onClick={() => setEditing({ id: m.id, text: m.body })}>✎</button>}
                  <button className="icon-btn" title="Delete" onClick={() => remove(m.id)}>🗑</button>
                </div>
              )}
            </div>
          );
        })}
      </div>
      <div className="composer">
        <form onSubmit={send}>
          <textarea rows={1} value={text} maxLength={target.kind === "dm" ? 500 : 2000} placeholder={placeholder}
            onChange={(e) => setText(e.target.value)}
            onKeyDown={(e) => { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); void send(); } }} />
          <button className="btn primary small" disabled={!text.trim()}>Send</button>
        </form>
      </div>
    </>
  );
}
