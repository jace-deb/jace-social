"use client";
import { useEffect, useState } from "react";
import {
  api, desktopReady, getToken, inDesktop, since, statusLabel, type Me, type Person, type ServerDetail, type Status,
} from "@/lib/client";
import { ActivityCard, Avatar, Modal, PersonRow } from "./ui";

type Err = (m: string) => void;

// ---------------------------------------------------------------------------------
// Someone's profile
// ---------------------------------------------------------------------------------
type FullProfile = Person & { is_friend: boolean; is_you: boolean; mutual_servers: { id: string; name: string; icon_url: string | null }[] };

export function ProfileModal({ uuid, onClose, onMessage, onChanged, onError }: {
  uuid: string; onClose: () => void; onMessage: (p: Person) => void; onChanged: () => void; onError: Err;
}) {
  const [p, setP] = useState<FullProfile | null>(null);
  useEffect(() => { api<FullProfile>(`/users/${uuid}`).then(setP).catch((e) => { onError(e.message); onClose(); }); }, [uuid]); // eslint-disable-line react-hooks/exhaustive-deps
  if (!p) return null;
  const accent = p.accent_color ?? "#2a4a37";
  async function addFriend() {
    try { await api("/friends", { body: { uuid } }); onChanged(); onClose(); } catch (e) { onError((e as Error).message); }
  }
  async function removeFriend() {
    if (!confirm(`Remove ${p!.name} from your friends?`)) return;
    try { await api(`/friends?uuid=${uuid}`, { method: "DELETE" }); onChanged(); onClose(); } catch (e) { onError((e as Error).message); }
  }
  return (
    <Modal onClose={onClose}>
      <div className="profile-banner" style={{ background: accent }} />
      <div className="profile-head"><Avatar p={p} size={92} status={p.status} /></div>
      <div className="modal-body">
        <div>
          <h2>{p.name}</h2>
          <div className="muted small">
            {[p.jace_name && `@${p.jace_name}`, p.minecraft_name && `⛏ ${p.minecraft_name}`, p.pronouns].filter(Boolean).join(" · ")}
          </div>
          <div className="small" style={{ marginTop: 6 }}>
            {statusLabel[p.status]}{p.custom_status && <> · {p.status_emoji} {p.custom_status}</>}
            {!p.online && p.last_seen && <span className="muted"> · last seen {since(p.last_seen)} ago</span>}
          </div>
        </div>
        <ActivityCard a={p.activity} />
        {p.bio && <div className="card"><div className="kind muted small" style={{ fontWeight: 800 }}>ABOUT ME</div><div style={{ whiteSpace: "pre-wrap" }}>{p.bio}</div></div>}
        {p.links.length > 0 && (
          <div className="chips">{p.links.map((l) => <a key={l.url} className="chip" href={l.url} target="_blank" rel="noopener noreferrer nofollow">🔗 {l.label}</a>)}</div>
        )}
        {p.mutual_servers.length > 0 && (
          <div className="small muted">Servers you share: {p.mutual_servers.map((s) => s.name).join(", ")}</div>
        )}
      </div>
      {!p.is_you && (
        <div className="modal-foot">
          {p.is_friend ? <>
            <button className="btn danger" onClick={removeFriend}>Remove friend</button>
            <button className="btn primary" onClick={() => { onMessage(p); onClose(); }}>Message</button>
          </> : <button className="btn primary" onClick={addFriend}>Add friend</button>}
        </div>
      )}
    </Modal>
  );
}

// ---------------------------------------------------------------------------------
// Your settings: profile, status, linked accounts
// ---------------------------------------------------------------------------------
export function SettingsModal({ me, onClose, onSaved, onSignOut, onError }: {
  me: Me; onClose: () => void; onSaved: (m: Me) => void; onSignOut: () => void; onError: Err;
}) {
  const [tab, setTab] = useState<"profile" | "status" | "accounts">("profile");
  const [f, setF] = useState({
    display_name: me.display_name ?? "", pronouns: me.pronouns ?? "", bio: me.bio ?? "",
    accent_color: me.accent_color ?? "#3ddc84", links: me.links.length ? me.links : [{ label: "", url: "" }],
  });
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState("");

  async function save() {
    setBusy(true);
    try {
      const m = await api<Me>("/me", { method: "PATCH", body: { ...f, links: f.links.filter((l) => l.url.trim()) } });
      onSaved(m); setNote("Saved!");
    } catch (e) { onError((e as Error).message); } finally { setBusy(false); }
  }

  async function avatar(file: File | null) {
    if (!file) return;
    try {
      const r = await api<{ avatar_url: string }>("/me/avatar", { raw: file });
      onSaved({ ...me, avatar_url: r.avatar_url });
    } catch (e) { onError((e as Error).message); }
  }

  async function linkJace() {
    setNote("Finish in the browser tab that opened…");
    try {
      const r = await api<{ url: string; state: string; poll_key: string }>("/link/jace", { body: {} });
      window.open(r.url, "_blank", "noopener");
      for (let i = 0; i < 150; i++) {                       // up to 5 minutes
        await new Promise((ok) => setTimeout(ok, 2000));
        const d = await api<{ pending?: boolean; jace_name?: string }>(`/auth/jace/poll?state=${r.state}&key=${r.poll_key}`);
        if (!d.pending) { onSaved(await api<Me>("/me")); setNote(`Linked @${d.jace_name}`); return; }
      }
      setNote("Linking timed out - try again");
    } catch (e) { setNote(""); onError((e as Error).message); }
  }

  async function linkMinecraft() {
    const d = await desktopReady();
    if (!d) return;
    setNote("Sign in with Microsoft in the window that opened…");
    const r = await d.linkMinecraft(getToken() ?? "");
    if (r.error) { setNote(""); onError(r.error); return; }
    onSaved(await api<Me>("/me")); setNote("Minecraft linked!");
  }

  async function unlinkJace() {
    if (!confirm("Unlink your Jace account? You'll sign in with Minecraft only.")) return;
    try { await api("/link/jace", { method: "DELETE" }); onSaved(await api<Me>("/me")); } catch (e) { onError((e as Error).message); }
  }

  return (
    <Modal onClose={onClose} wide>
      <div className="settings">
        <nav>
          {(["profile", "status", "accounts"] as const).map((t) => (
            <button key={t} className={`side-item${tab === t ? " active" : ""}`} onClick={() => { setTab(t); setNote(""); }}>
              {t === "profile" ? "My profile" : t === "status" ? "Status" : "Linked accounts"}</button>
          ))}
          <button className="side-item" style={{ color: "var(--red)" }} onClick={onSignOut}>Sign out</button>
        </nav>
        <div className="modal-body">
          {tab === "profile" && <>
            <h2>My profile</h2>
            <div style={{ display: "flex", gap: 14, alignItems: "center" }}>
              <Avatar p={me} size={72} />
              <div style={{ display: "grid", gap: 6 }}>
                <label className="btn small" style={{ textTransform: "none", color: "var(--text)", fontSize: 13 }}>
                  Change picture<input type="file" accept="image/png,image/jpeg,image/gif,image/webp" hidden
                    onChange={(e) => avatar(e.target.files?.[0] ?? null)} /></label>
                {me.avatar_url && !me.avatar_url.includes("mc-heads.net") && (
                  <button className="btn small" onClick={async () => { await api("/me/avatar", { method: "DELETE" }); onSaved(await api<Me>("/me")); }}>Remove picture</button>
                )}
              </div>
            </div>
            <label>Display name<input value={f.display_name} maxLength={32} placeholder={me.minecraft_name ?? me.jace_name ?? ""}
              onChange={(e) => setF({ ...f, display_name: e.target.value })} /></label>
            <label>Pronouns<input value={f.pronouns} maxLength={24} onChange={(e) => setF({ ...f, pronouns: e.target.value })} /></label>
            <label>About me<textarea rows={4} value={f.bio} maxLength={300} onChange={(e) => setF({ ...f, bio: e.target.value })} /></label>
            <label>Profile color<input type="color" value={f.accent_color} style={{ height: 40, padding: 4 }}
              onChange={(e) => setF({ ...f, accent_color: e.target.value })} /></label>
            <label>Links (up to 5)</label>
            {f.links.map((l, i) => (
              <div key={i} style={{ display: "grid", gridTemplateColumns: "1fr 2fr auto", gap: 6 }}>
                <input placeholder="Name" value={l.label} maxLength={32}
                  onChange={(e) => setF({ ...f, links: f.links.map((x, j) => (j === i ? { ...x, label: e.target.value } : x)) })} />
                <input placeholder="https://…" value={l.url} maxLength={300}
                  onChange={(e) => setF({ ...f, links: f.links.map((x, j) => (j === i ? { ...x, url: e.target.value } : x)) })} />
                <button className="icon-btn" aria-label="Remove link" onClick={() => setF({ ...f, links: f.links.filter((_, j) => j !== i) })}>✕</button>
              </div>
            ))}
            {f.links.length < 5 && <button className="btn small" style={{ justifySelf: "start" }}
              onClick={() => setF({ ...f, links: [...f.links, { label: "", url: "" }] })}>+ Add link</button>}
            <div style={{ display: "flex", gap: 10, alignItems: "center" }}>
              <button className="btn primary" disabled={busy} onClick={save}>{busy ? "Saving…" : "Save profile"}</button>
              <span className="muted small">{note}</span>
            </div>
          </>}
          {tab === "status" && <StatusEditor me={me} onSaved={onSaved} onError={onError} />}
          {tab === "accounts" && <>
            <h2>Linked accounts</h2>
            <p className="muted">Link both to sign in either way. Your friends, chats and servers stay with you.</p>
            <div className="card" style={{ display: "flex", alignItems: "center", gap: 12 }}>
              <div style={{ flex: 1 }}><b>Jace</b><div className="muted small">{me.jace_linked ? `@${me.jace_name}` : "Not linked"}</div></div>
              {me.jace_linked
                ? me.minecraft_linked && <button className="btn small" onClick={unlinkJace}>Unlink</button>
                : <button className="btn primary small" onClick={linkJace}>Link Jace</button>}
            </div>
            <div className="card" style={{ display: "flex", alignItems: "center", gap: 12 }}>
              <div style={{ flex: 1 }}><b>Minecraft</b><div className="muted small">{me.minecraft_linked ? me.minecraft_name : "Not linked"}</div></div>
              {!me.minecraft_linked && (inDesktop()
                ? <button className="btn primary small" onClick={linkMinecraft}>Link Minecraft</button>
                : <span className="muted small" style={{ maxWidth: 220 }}>Link in the Jace Social desktop app, or in Jace Launcher (Settings → Link Jace)</span>)}
            </div>
            {note && <div className="muted small">{note}</div>}
          </>}
        </div>
      </div>
    </Modal>
  );
}

export function StatusEditor({ me, onSaved, onError }: { me: Me; onSaved: (m: Me) => void; onError: Err }) {
  const [custom, setCustom] = useState(me.custom_status ?? "");
  const [emoji, setEmoji] = useState(me.status_emoji ?? "");
  async function set(body: Record<string, unknown>) {
    try { onSaved(await api<Me>("/me", { method: "PATCH", body })); } catch (e) { onError((e as Error).message); }
  }
  return <>
    <h2>Status</h2>
    <div style={{ display: "grid", gap: 4 }}>
      {(["online", "idle", "dnd", "invisible"] as Status[]).map((s) => (
        <button key={s} className={`side-item${me.status === s ? " active" : ""}`} onClick={() => set({ status: s })}>
          <span className="avatar" style={{ width: 14, height: 14 }}><span className={`dot ${s}`} style={{ inset: 0, width: 14, height: 14, border: 0 }} /></span>
          <span className="name">{statusLabel[s]}<span className="sub">{s === "dnd" ? "No notification pop-ups" : s === "invisible" ? "You'll look offline, but can still use everything" : s === "idle" ? "Show that you're away" : "Show that you're around"}</span></span>
        </button>
      ))}
    </div>
    <label>Custom status</label>
    <div style={{ display: "grid", gridTemplateColumns: "64px 1fr auto auto", gap: 6 }}>
      <input placeholder="😀" value={emoji} maxLength={16} onChange={(e) => setEmoji(e.target.value)} />
      <input placeholder="What's up?" value={custom} maxLength={80} onChange={(e) => setCustom(e.target.value)} />
      <button className="btn primary small" onClick={() => set({ custom_status: custom, status_emoji: emoji })}>Set</button>
      <button className="btn small" onClick={() => { setCustom(""); setEmoji(""); void set({ custom_status: null, status_emoji: null }); }}>Clear</button>
    </div>
  </>;
}

// ---------------------------------------------------------------------------------
// New group chat
// ---------------------------------------------------------------------------------
export function NewGroupModal({ friends, onClose, onCreated, onError }: {
  friends: Person[]; onClose: () => void; onCreated: (id: string) => void; onError: Err;
}) {
  const [picked, setPicked] = useState<string[]>([]);
  const [name, setName] = useState("");
  async function create() {
    try {
      const r = await api<{ group: { id: string } }>("/groups", { body: { name, members: picked } });
      onCreated(r.group.id); onClose();
    } catch (e) { onError((e as Error).message); }
  }
  return (
    <Modal onClose={onClose}>
      <div className="modal-body">
        <h2>New group chat</h2>
        <p className="muted small" style={{ margin: 0 }}>Pick up to 9 friends.</p>
        <input placeholder="Group name (optional)" value={name} maxLength={48} onChange={(e) => setName(e.target.value)} />
        <div style={{ maxHeight: 320, overflowY: "auto" }}>
          {friends.length === 0 && <p className="muted">Add some friends first.</p>}
          {friends.map((p) => (
            <PersonRow key={p.uuid} p={p} onClick={() => setPicked((x) => x.includes(p.uuid) ? x.filter((u) => u !== p.uuid) : x.length < 9 ? [...x, p.uuid] : x)}>
              <input type="checkbox" style={{ width: 18 }} checked={picked.includes(p.uuid)} readOnly />
            </PersonRow>
          ))}
        </div>
      </div>
      <div className="modal-foot">
        <button className="btn" onClick={onClose}>Cancel</button>
        <button className="btn primary" disabled={!picked.length} onClick={create}>Create group</button>
      </div>
    </Modal>
  );
}

// ---------------------------------------------------------------------------------
// Create or join a server
// ---------------------------------------------------------------------------------
export function AddServerModal({ onClose, onDone, onError, initialCode }: {
  onClose: () => void; onDone: (id: string) => void; onError: Err; initialCode?: string;
}) {
  const [name, setName] = useState("");
  const [code, setCode] = useState(initialCode ?? "");
  async function create() {
    try { const r = await api<{ server: { id: string } }>("/servers", { body: { name } }); onDone(r.server.id); onClose(); }
    catch (e) { onError((e as Error).message); }
  }
  async function join() {
    const c = code.trim().replace(/^.*\/invite\//, "");
    try { const r = await api<{ server: { id: string } }>(`/invites/${encodeURIComponent(c)}`, { body: {} }); onDone(r.server.id); onClose(); }
    catch (e) { onError((e as Error).message); }
  }
  return (
    <Modal onClose={onClose}>
      <div className="modal-body">
        <h2>Create a server</h2>
        <p className="muted small" style={{ margin: 0 }}>A place for you and your friends, with text channels.</p>
        <div style={{ display: "flex", gap: 8 }}>
          <input placeholder="Server name" value={name} maxLength={48} onChange={(e) => setName(e.target.value)}
            onKeyDown={(e) => { if (e.key === "Enter" && name.trim()) void create(); }} />
          <button className="btn primary" disabled={!name.trim()} onClick={create}>Create</button>
        </div>
        <h2 style={{ marginTop: 10 }}>Join a server</h2>
        <div style={{ display: "flex", gap: 8 }}>
          <input placeholder="Invite code or link" value={code} onChange={(e) => setCode(e.target.value)}
            onKeyDown={(e) => { if (e.key === "Enter" && code.trim()) void join(); }} />
          <button className="btn primary" disabled={!code.trim()} onClick={join}>Join</button>
        </div>
      </div>
    </Modal>
  );
}

// ---------------------------------------------------------------------------------
// Server settings (admins) / leave (members)
// ---------------------------------------------------------------------------------
export function ServerSettingsModal({ detail, me, onClose, onChanged, onLeft, onError }: {
  detail: ServerDetail; me: Me; onClose: () => void; onChanged: () => void; onLeft: () => void; onError: Err;
}) {
  const { server, role, channels, members } = detail;
  const admin = role !== "member";
  const [name, setName] = useState(server.name);
  const [desc, setDesc] = useState(server.description ?? "");
  const [newChannel, setNewChannel] = useState("");
  const [code, setCode] = useState(server.invite_code);
  const link = code ? `${location.origin}/invite/${code}` : "";
  const run = async (fn: () => Promise<unknown>) => { try { await fn(); onChanged(); } catch (e) { onError((e as Error).message); } };

  return (
    <Modal onClose={onClose} wide>
      <div className="modal-body">
        <h2>{server.name}</h2>
        {admin && <>
          <div style={{ display: "flex", gap: 14, alignItems: "center" }}>
            <Avatar p={{ name: server.name, avatar_url: server.icon_url }} size={64} />
            <label className="btn small" style={{ textTransform: "none", color: "var(--text)", fontSize: 13 }}>
              Change icon<input type="file" accept="image/png,image/jpeg,image/gif,image/webp" hidden
                onChange={(e) => { const f = e.target.files?.[0]; if (f) void run(() => api(`/servers/${server.id}/icon`, { raw: f })); }} /></label>
          </div>
          <label>Server name<input value={name} maxLength={48} onChange={(e) => setName(e.target.value)} /></label>
          <label>Description<textarea rows={2} value={desc} maxLength={300} onChange={(e) => setDesc(e.target.value)} /></label>
          <button className="btn primary" style={{ justifySelf: "start" }}
            onClick={() => run(() => api(`/servers/${server.id}`, { method: "PATCH", body: { name, description: desc } }))}>Save</button>

          <label>Invite link</label>
          <div style={{ display: "flex", gap: 8 }}>
            <input readOnly value={link} onFocus={(e) => e.target.select()} />
            <button className="btn" onClick={() => navigator.clipboard?.writeText(link)}>Copy</button>
            <button className="btn" onClick={async () => { try { const r = await api<{ invite_code: string }>(`/servers/${server.id}/invite`, { body: {} }); setCode(r.invite_code); } catch (e) { onError((e as Error).message); } }}>New link</button>
          </div>

          <label>Channels</label>
          {channels.map((c) => (
            <div key={c.id} style={{ display: "flex", gap: 8, alignItems: "center" }}>
              <span className="hash">#</span><span style={{ flex: 1 }}>{c.name}</span>
              <button className="btn small" onClick={() => { const n = prompt("Channel name", c.name); if (n) void run(() => api(`/channels/${c.id}`, { method: "PATCH", body: { name: n } })); }}>Rename</button>
              <button className="btn small" onClick={() => { const t = prompt("Channel topic", c.topic ?? ""); if (t !== null) void run(() => api(`/channels/${c.id}`, { method: "PATCH", body: { topic: t } })); }}>Topic</button>
              <button className="btn danger small" disabled={channels.length <= 1}
                onClick={() => { if (confirm(`Delete #${c.name} and all its messages?`)) void run(() => api(`/channels/${c.id}`, { method: "DELETE" })); }}>Delete</button>
            </div>
          ))}
          <div style={{ display: "flex", gap: 8 }}>
            <input placeholder="new-channel" value={newChannel} maxLength={48} onChange={(e) => setNewChannel(e.target.value)} />
            <button className="btn" disabled={!newChannel.trim()}
              onClick={() => run(async () => { await api(`/servers/${server.id}/channels`, { body: { name: newChannel } }); setNewChannel(""); })}>Add channel</button>
          </div>

          <label>Members ({members.length})</label>
          <div style={{ maxHeight: 260, overflowY: "auto" }}>
            {members.map((m) => (
              <PersonRow key={m.uuid} p={m}>
                <span className="chip">{m.role}</span>
                {role === "owner" && m.uuid !== me.uuid && <>
                  <button className="btn small" onClick={() => run(() => api(`/servers/${server.id}/members`, { method: "PATCH", body: { uuid: m.uuid, role: m.role === "admin" ? "member" : "admin" } }))}>
                    {m.role === "admin" ? "Remove admin" : "Make admin"}</button>
                  <button className="btn small" onClick={() => { if (confirm(`Give ${server.name} to ${m.name}? You'll become an admin.`)) void run(() => api(`/servers/${server.id}/members`, { method: "PATCH", body: { uuid: m.uuid, role: "owner" } })); }}>Make owner</button>
                </>}
                {m.uuid !== me.uuid && m.role !== "owner" && (role === "owner" || m.role === "member") && (
                  <button className="btn danger small" onClick={() => { if (confirm(`Remove ${m.name} from the server?`)) void run(() => api(`/servers/${server.id}/members?uuid=${m.uuid}`, { method: "DELETE" })); }}>Kick</button>
                )}
              </PersonRow>
            ))}
          </div>
        </>}
      </div>
      <div className="modal-foot">
        {role === "owner"
          ? <button className="btn danger" onClick={async () => { if (prompt(`Type the server name to delete it forever:`) === server.name) { try { await api(`/servers/${server.id}`, { method: "DELETE" }); onLeft(); onClose(); } catch (e) { onError((e as Error).message); } } }}>Delete server</button>
          : <button className="btn danger" onClick={async () => { if (confirm(`Leave ${server.name}?`)) { try { await api(`/servers/${server.id}/members?uuid=${me.uuid}`, { method: "DELETE" }); onLeft(); onClose(); } catch (e) { onError((e as Error).message); } } }}>Leave server</button>}
        <button className="btn" onClick={onClose}>Done</button>
      </div>
    </Modal>
  );
}
