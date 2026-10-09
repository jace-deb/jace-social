"use client";
// Server settings: overview, roles and permissions, members, invites and the custom link,
// onboarding (rules, welcome, questions that give roles), bans and bots. Each tab shows if
// your permissions allow it.
import { useEffect, useState } from "react";
import { api, type Me, type Onboarding, type Person, type Role, type ServerDetail } from "@/lib/client";
import { ALL, has, P, PERMISSION_INFO } from "@/lib/permbits";
import { ACCENTS } from "@/lib/theme";
import { Avatar, Modal } from "./ui";

type Err = (m: string) => void;
type Tab = "overview" | "roles" | "members" | "invites" | "onboarding" | "bans" | "bots";

export const inviteLink = (code: string) => `${typeof location !== "undefined" ? location.origin : "https://jace-social.vercel.app"}/${code}`;

export function ServerSettings({ detail, me, onClose, onChanged, onLeft, onError, startTab }: {
  detail: ServerDetail; me: Me; onClose: () => void; onChanged: () => void; onLeft: () => void; onError: Err; startTab?: Tab;
}) {
  const perms = detail.perms;
  const tabs: [Tab, string, boolean][] = [
    ["overview", "Overview", has(perms, P.MANAGE_SERVER)],
    ["roles", "Roles", has(perms, P.MANAGE_ROLES)],
    ["members", "Members", has(perms, P.KICK) || has(perms, P.BAN) || has(perms, P.MANAGE_ROLES) || has(perms, P.MANAGE_NICKS) || has(perms, P.TIMEOUT)],
    ["invites", "Invites", has(perms, P.INVITE) || has(perms, P.MANAGE_SERVER)],
    ["onboarding", "Onboarding", has(perms, P.MANAGE_SERVER)],
    ["bans", "Bans", has(perms, P.BAN)],
    ["bots", "Bots", has(perms, P.MANAGE_BOTS)],
  ];
  const visible = tabs.filter((t) => t[2]);
  const [tab, setTab] = useState<Tab>(startTab && visible.some((t) => t[0] === startTab) ? startTab : visible[0]?.[0] ?? "overview");
  const run = async (fn: () => Promise<unknown>) => { try { await fn(); onChanged(); return true; } catch (e) { onError((e as Error).message); return false; } };
  const sid = detail.server.id;

  return (
    <Modal onClose={onClose} wide>
      <div className="settings">
        <nav>
          <div className="side-label" style={{ paddingTop: 4 }}>{detail.server.name}</div>
          {visible.map(([t, label]) => (
            <button key={t} className={`side-item${tab === t ? " active" : ""}`} onClick={() => setTab(t)}>{label}</button>
          ))}
          {detail.owner
            ? <button className="side-item" style={{ color: "var(--red)" }} onClick={async () => {
                if (prompt("Type the server name to delete it forever:") !== detail.server.name) return;
                if (await run(() => api(`/servers/${sid}`, { method: "DELETE" }))) { onLeft(); onClose(); }
              }}>Delete server</button>
            : <button className="side-item" style={{ color: "var(--red)" }} onClick={async () => {
                if (!confirm(`Leave ${detail.server.name}?`)) return;
                if (await run(() => api(`/servers/${sid}/members?uuid=${me.uuid}`, { method: "DELETE" }))) { onLeft(); onClose(); }
              }}>Leave server</button>}
        </nav>
        <div className="modal-body">
          {!visible.length && <p className="muted">You don't have permission to change this server's settings.</p>}
          {tab === "overview" && visible.some((t) => t[0] === "overview") && <OverviewTab detail={detail} run={run} />}
          {tab === "roles" && <RolesTab detail={detail} run={run} onError={onError} />}
          {tab === "members" && <MembersTab detail={detail} me={me} run={run} />}
          {tab === "invites" && <InvitesTab detail={detail} run={run} onError={onError} />}
          {tab === "onboarding" && <OnboardingTab detail={detail} run={run} />}
          {tab === "bans" && <BansTab detail={detail} run={run} onError={onError} />}
          {tab === "bots" && <BotsTab detail={detail} run={run} onError={onError} />}
        </div>
      </div>
      <div className="modal-foot"><button className="btn" onClick={onClose}>Done</button></div>
    </Modal>
  );
}

type Run = (fn: () => Promise<unknown>) => Promise<boolean>;

function OverviewTab({ detail, run }: { detail: ServerDetail; run: Run }) {
  const s = detail.server;
  const [name, setName] = useState(s.name);
  const [desc, setDesc] = useState(s.description ?? "");
  const [accent, setAccent] = useState(s.accent_color ?? "");
  const [system, setSystem] = useState(s.system_channel ?? "");
  const textChannels = detail.channels.filter((c) => c.kind === "text" || c.kind === "announcement" || !c.kind);
  const pick = (url: string) => (e: React.ChangeEvent<HTMLInputElement>) => {
    const f = e.target.files?.[0];
    if (f) void run(() => api(url, { raw: f }));
  };
  return <>
    <h2>Overview</h2>
    <div className="server-banner-edit" style={{ background: s.banner_url ? `center / cover url(${s.banner_url})` : (s.accent_color ?? "var(--panel2)") }}>
      <label className="btn small">Change banner<input type="file" accept="image/png,image/jpeg,image/gif,image/webp" hidden onChange={pick(`/servers/${s.id}/banner`)} /></label>
      {s.banner_url && <button className="btn small" onClick={() => void run(() => api(`/servers/${s.id}/banner`, { method: "DELETE" }))}>Remove</button>}
    </div>
    <div style={{ display: "flex", gap: 14, alignItems: "center" }}>
      <Avatar p={{ name: s.name, avatar_url: s.icon_url }} size={64} />
      <label className="btn small" style={{ textTransform: "none", color: "var(--text)", fontSize: 13 }}>
        Change icon<input type="file" accept="image/png,image/jpeg,image/gif,image/webp" hidden onChange={pick(`/servers/${s.id}/icon`)} /></label>
    </div>
    <label>Server name<input value={name} maxLength={48} onChange={(e) => setName(e.target.value)} /></label>
    <label>Description<textarea rows={2} value={desc} maxLength={300} onChange={(e) => setDesc(e.target.value)} /></label>
    <label>Accent color
      <div className="swatches">
        <button className={`swatch${!accent ? " on" : ""}`} style={{ background: "var(--panel2)" }} onClick={() => setAccent("")} title="None" />
        {ACCENTS.map((c) => <button key={c} className={`swatch${accent === c ? " on" : ""}`} style={{ background: c }} onClick={() => setAccent(c)} />)}
        <input type="color" value={accent || "#3ddc84"} onChange={(e) => setAccent(e.target.value)} style={{ width: 40, padding: 2 }} />
      </div>
    </label>
    <label>Messages like “X joined” go to
      <select value={system} onChange={(e) => setSystem(e.target.value)}>
        <option value="">Nowhere (no join messages)</option>
        {textChannels.map((c) => <option key={c.id} value={c.id}>#{c.name}</option>)}
      </select>
    </label>
    <button className="btn primary" style={{ justifySelf: "start" }}
      onClick={() => void run(() => api(`/servers/${s.id}`, { method: "PATCH", body: { name, description: desc, accent_color: accent || null, system_channel: system || null } }))}>Save</button>
  </>;
}

export function PermissionList({ value, onChange, editable, allowedBits, tri }: {
  value: number; onChange: (v: number) => void; editable: number; allowedBits?: number; tri?: { allow: number; deny: number; set: (a: number, d: number) => void };
}) {
  return (
    <div className="perm-list">
      {PERMISSION_INFO.map((g) => {
        const items = g.items.filter(([bit, , , channel]) => (allowedBits === undefined || (allowedBits & bit)) && (!tri || channel));
        if (!items.length) return null;
        return (
          <div key={g.group}>
            <div className="side-label">{g.group}</div>
            {items.map(([bit, label, desc]) => (
              <div key={bit} className="perm-row">
                <div className="grow"><b>{label}</b>{desc && <div className="muted small">{desc}</div>}</div>
                {tri ? (
                  <div className="tri">
                    {(["deny", "inherit", "allow"] as const).map((s) => {
                      const cur = tri.allow & bit ? "allow" : tri.deny & bit ? "deny" : "inherit";
                      return <button key={s} disabled={!(editable & bit)} className={`tri-${s}${cur === s ? " on" : ""}`} title={s === "inherit" ? "Use the role's setting" : s}
                        onClick={() => {
                          let a = tri.allow & ~bit, d = tri.deny & ~bit;
                          if (s === "allow") a |= bit;
                          if (s === "deny") d |= bit;
                          tri.set(a, d);
                        }}>{s === "deny" ? "✕" : s === "allow" ? "✓" : "/"}</button>;
                    })}
                  </div>
                ) : (
                  <input type="checkbox" className="toggle" checked={has(value, bit)} disabled={!(editable & bit)}
                    onChange={(e) => onChange(e.target.checked ? value | bit : value & ~bit)} />
                )}
              </div>
            ))}
          </div>
        );
      })}
    </div>
  );
}

function RolesTab({ detail, run, onError }: { detail: ServerDetail; run: Run; onError: Err }) {
  const roles = [...detail.roles].sort((a, b) => b.position - a.position);
  const [selected, setSelected] = useState<string>(roles.find((r) => !r.is_default)?.id ?? roles[0]?.id);
  const role = roles.find((r) => r.id === selected) ?? roles[0];
  const [draft, setDraft] = useState<Role | null>(role ?? null);
  useEffect(() => { setDraft(role ?? null); }, [selected, detail]);   // eslint-disable-line react-hooks/exhaustive-deps
  const canEdit = (r: Role) => detail.owner || r.is_default || r.position < detail.top;
  const editable = detail.owner ? ALL : detail.perms;
  const movable = roles.filter((r) => !r.is_default && canEdit(r));
  const move = (id: string, dir: -1 | 1) => {
    const order = movable.map((r) => r.id);
    const i = order.indexOf(id), j = i + dir;
    if (i < 0 || j < 0 || j >= order.length) return;
    [order[i], order[j]] = [order[j], order[i]];
    void run(() => api(`/servers/${detail.server.id}/roles`, { method: "PUT", body: { order } }));
  };
  const count = (r: Role) => r.is_default ? detail.members.length : detail.members.filter((m) => m.roles?.includes(r.id)).length;
  return <>
    <h2>Roles</h2>
    <p className="muted small" style={{ margin: 0 }}>People get every permission from all their roles. Higher roles can manage the ones below them. The @everyone role is what everybody gets.</p>
    <div className="roles-layout">
      <div className="roles-list">
        <button className="btn small primary" onClick={async () => {
          const name = prompt("Role name", "new role");
          if (!name) return;
          try {
            const r = await api<{ role: Role }>(`/servers/${detail.server.id}/roles`, { body: { name, color: ACCENTS[roles.length % ACCENTS.length] } });
            await run(async () => {});
            setSelected(r.role.id);
          } catch (e) { onError((e as Error).message); }
        }}>＋ Create role</button>
        {roles.map((r) => (
          <div key={r.id} className={`role-item${r.id === selected ? " active" : ""}`} onClick={() => setSelected(r.id)}>
            <span className="role-dot" style={{ background: r.color ?? "var(--gray)" }} />
            <span className="grow">{r.icon} {r.name}</span>
            <span className="muted small">{count(r)}</span>
            {!r.is_default && canEdit(r) && <>
              <button className="icon-btn small" onClick={(e) => { e.stopPropagation(); move(r.id, -1); }} title="Move up">▲</button>
              <button className="icon-btn small" onClick={(e) => { e.stopPropagation(); move(r.id, 1); }} title="Move down">▼</button>
            </>}
          </div>
        ))}
      </div>
      {draft && (
        <div className="role-editor">
          {!canEdit(draft) && <p className="muted small">This role is the same as or above your highest role, so you can't change it.</p>}
          <fieldset disabled={!canEdit(draft)} style={{ border: 0, padding: 0, margin: 0, display: "grid", gap: 12 }}>
            {!draft.is_default && <>
              <label>Role name<input value={draft.name} maxLength={32} onChange={(e) => setDraft({ ...draft, name: e.target.value })} /></label>
              <label>Color
                <div className="swatches">
                  <button className={`swatch${!draft.color ? " on" : ""}`} style={{ background: "var(--gray)" }} onClick={() => setDraft({ ...draft, color: null })} title="Default" />
                  {ACCENTS.map((c) => <button key={c} className={`swatch${draft.color === c ? " on" : ""}`} style={{ background: c }} onClick={() => setDraft({ ...draft, color: c })} />)}
                  <input type="color" value={draft.color ?? "#99aab5"} onChange={(e) => setDraft({ ...draft, color: e.target.value })} style={{ width: 40, padding: 2 }} />
                </div>
              </label>
              <label>Icon (an emoji)<input value={draft.icon ?? ""} maxLength={16} placeholder="⭐" onChange={(e) => setDraft({ ...draft, icon: e.target.value || null })} /></label>
              <label className="check"><input type="checkbox" checked={draft.hoist} onChange={(e) => setDraft({ ...draft, hoist: e.target.checked })} /> Show members with this role separately</label>
              <label className="check"><input type="checkbox" checked={draft.mentionable} onChange={(e) => setDraft({ ...draft, mentionable: e.target.checked })} /> Anyone can @mention this role</label>
            </>}
            <PermissionList value={draft.permissions} onChange={(v) => setDraft({ ...draft, permissions: v })} editable={canEdit(draft) ? editable : 0} />
            <div style={{ display: "flex", gap: 8 }}>
              <button className="btn primary" onClick={() => void run(() => api(`/servers/${detail.server.id}/roles/${draft.id}`, { method: "PATCH",
                body: { name: draft.name, color: draft.color, icon: draft.icon, hoist: draft.hoist, mentionable: draft.mentionable, permissions: draft.permissions } }))}>Save role</button>
              {!draft.is_default && <button className="btn danger" onClick={() => { if (confirm(`Delete the ${draft.name} role?`)) void run(() => api(`/servers/${detail.server.id}/roles/${draft.id}`, { method: "DELETE" })); }}>Delete role</button>}
            </div>
          </fieldset>
        </div>
      )}
    </div>
  </>;
}

/** Roles, nickname, timeout, kick and ban for one member (also used from the member list). */
export function MemberActions({ detail, member, me, run }: { detail: ServerDetail; member: Person; me: Me; run: Run }) {
  const sid = detail.server.id;
  const isOwner = member.uuid === detail.server.owner;
  const theirTop = isOwner ? 1e9 : Math.max(0, ...detail.roles.filter((r) => member.roles?.includes(r.id)).map((r) => r.position));
  const above = detail.owner || (member.uuid !== me.uuid && theirTop < detail.top);
  const assignable = detail.roles.filter((r) => !r.is_default && (detail.owner || r.position < detail.top)).sort((a, b) => b.position - a.position);
  const timedOut = member.timeout_until && new Date(member.timeout_until) > new Date();
  return (
    <div className="member-actions">
      {has(detail.perms, P.MANAGE_ROLES) && (above || member.uuid === me.uuid) && assignable.length > 0 && (
        <div className="chips">
          {assignable.map((r) => {
            const on = member.roles?.includes(r.id);
            return <button key={r.id} className={`chip role-chip${on ? " on" : ""}`} style={on && r.color ? { borderColor: r.color } : undefined}
              onClick={() => void run(() => api(`/servers/${sid}/members`, { method: "PATCH", body: { uuid: member.uuid, roles: on ? member.roles!.filter((x) => x !== r.id) : [...(member.roles ?? []), r.id] } }))}>
              <span className="role-dot" style={{ background: r.color ?? "var(--gray)" }} /> {r.name} {on ? "✓" : "＋"}</button>;
          })}
        </div>
      )}
      <div style={{ display: "flex", gap: 6, flexWrap: "wrap" }}>
        {(member.uuid === me.uuid ? has(detail.perms, P.CHANGE_NICK) : has(detail.perms, P.MANAGE_NICKS) && above) && (
          <button className="btn small" onClick={() => { const n = prompt("Nickname in this server (empty to remove)", member.nickname ?? ""); if (n !== null) void run(() => api(`/servers/${sid}/members`, { method: "PATCH", body: { uuid: member.uuid, nickname: n } })); }}>Nickname</button>
        )}
        {member.uuid !== me.uuid && above && !isOwner && <>
          {has(detail.perms, P.TIMEOUT) && (timedOut
            ? <button className="btn small" onClick={() => void run(() => api(`/servers/${sid}/members`, { method: "PATCH", body: { uuid: member.uuid, timeout_until: null } }))}>End timeout</button>
            : <select className="btn small" value="" onChange={(e) => { const m = Number(e.target.value); if (m) void run(() => api(`/servers/${sid}/members`, { method: "PATCH", body: { uuid: member.uuid, timeout_until: new Date(Date.now() + m * 60_000).toISOString() } })); }}>
                <option value="">Time out…</option><option value={1}>1 minute</option><option value={5}>5 minutes</option><option value={10}>10 minutes</option>
                <option value={60}>1 hour</option><option value={1440}>1 day</option><option value={10080}>1 week</option>
              </select>)}
          {has(detail.perms, P.KICK) && <button className="btn small danger" onClick={() => { if (confirm(`Kick ${member.name}? They can join again with an invite.`)) void run(() => api(`/servers/${sid}/members?uuid=${member.uuid}`, { method: "DELETE" })); }}>Kick</button>}
          {has(detail.perms, P.BAN) && <button className="btn small danger" onClick={() => {
            const reason = prompt(`Ban ${member.name}? They won't be able to come back.\nReason (optional):`);
            if (reason !== null) void run(() => api(`/servers/${sid}/bans`, { body: { uuid: member.uuid, reason, delete_messages: confirm("Also delete their messages from the last 7 days?") } }));
          }}>Ban</button>}
        </>}
        {detail.owner && member.uuid !== me.uuid && !member.is_bot && (
          <button className="btn small" onClick={() => { if (confirm(`Give ${detail.server.name} to ${member.name}? You'll stop being the owner.`)) void run(() => api(`/servers/${sid}/members`, { method: "PATCH", body: { uuid: member.uuid, role: "owner" } })); }}>Make owner</button>
        )}
      </div>
      {timedOut && <span className="muted small">Timed out until {new Date(member.timeout_until!).toLocaleString()}</span>}
    </div>
  );
}

function MembersTab({ detail, me, run }: { detail: ServerDetail; me: Me; run: Run }) {
  const [q, setQ] = useState("");
  const list = detail.members.filter((m) => `${m.nickname ?? ""} ${m.name}`.toLowerCase().includes(q.toLowerCase()))
    .sort((a, b) => a.name.localeCompare(b.name));
  return <>
    <h2>Members ({detail.members.length})</h2>
    <input placeholder="Search members" value={q} onChange={(e) => setQ(e.target.value)} />
    {list.map((m) => (
      <div key={m.uuid} className="card member-card">
        <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
          <Avatar p={m} size={32} status={m.status} />
          <div className="grow"><b>{m.nickname || m.name}</b>{m.is_bot && <span className="bot-tag" style={{ marginLeft: 6 }}>BOT</span>}
            {m.uuid === detail.server.owner && <span title="Owner"> 👑</span>}
            <div className="muted small">{m.nickname ? m.name : ""}{m.joined_at ? ` joined ${new Date(m.joined_at).toLocaleDateString()}` : ""}</div></div>
        </div>
        <MemberActions detail={detail} member={m} me={me} run={run} />
      </div>
    ))}
  </>;
}

function InvitesTab({ detail, run, onError }: { detail: ServerDetail; run: Run; onError: Err }) {
  const s = detail.server;
  const [code, setCode] = useState(s.invite_code);
  const [vanity, setVanity] = useState(s.vanity ?? "");
  const canManage = has(detail.perms, P.MANAGE_SERVER);
  const link = code ? inviteLink(code) : "";
  return <>
    <h2>Invites</h2>
    {code ? <>
      <label>Invite link</label>
      <div style={{ display: "flex", gap: 8 }}>
        <input readOnly value={link} onFocus={(e) => e.target.select()} />
        <button className="btn" onClick={() => navigator.clipboard?.writeText(link)}>Copy</button>
        <button className="btn" onClick={async () => { if (!confirm("Make a new link? The old one will stop working.")) return; try { const r = await api<{ invite_code: string }>(`/servers/${s.id}/invite`, { body: {} }); setCode(r.invite_code); } catch (e) { onError((e as Error).message); } }}>New link</button>
      </div>
      <p className="muted small" style={{ margin: 0 }}>Opening the link shows a page where people choose the desktop app or the web.</p>
    </> : <p className="muted">You can't create invites in this server.</p>}
    {canManage && <>
      <label>Custom link
        <div className="vanity">
          <span className="muted">{typeof location !== "undefined" ? location.host : "jace-social.vercel.app"}/</span>
          <input value={vanity} maxLength={32} placeholder="yourserver" onChange={(e) => setVanity(e.target.value.toLowerCase().replace(/[^a-z0-9-]/g, ""))} />
          <button className="btn primary" onClick={() => void run(() => api(`/servers/${s.id}`, { method: "PATCH", body: { vanity } }))}>Save</button>
        </div>
      </label>
      <p className="muted small" style={{ margin: 0 }}>3-32 lowercase letters, numbers and dashes. It works like an invite that never changes.</p>
      {s.vanity && <div style={{ display: "flex", gap: 8 }}><input readOnly value={inviteLink(s.vanity)} /><button className="btn" onClick={() => navigator.clipboard?.writeText(inviteLink(s.vanity!))}>Copy</button></div>}
      <label className="check"><input type="checkbox" checked={s.invites_paused} onChange={(e) => void run(() => api(`/servers/${s.id}`, { method: "PATCH", body: { invites_paused: e.target.checked } }))} />
        Pause invites (nobody new can join for now)</label>
    </>}
  </>;
}

function OnboardingTab({ detail, run }: { detail: ServerDetail; run: Run }) {
  const s = detail.server;
  const [rules, setRules] = useState(s.rules ?? "");
  const [welcome, setWelcome] = useState(s.welcome?.message ?? "");
  const [welcomeChannels, setWelcomeChannels] = useState(s.welcome?.channels ?? []);
  const [questions, setQuestions] = useState<Onboarding>(s.onboarding ?? []);
  const roles = detail.roles.filter((r) => !r.is_default).sort((a, b) => b.position - a.position);
  const textChannels = detail.channels.filter((c) => c.kind !== "category" && c.kind !== "voice");
  const setQ = (i: number, q: Onboarding[number]) => setQuestions(questions.map((x, j) => (j === i ? q : x)));
  return <>
    <h2>Onboarding</h2>
    <p className="muted small" style={{ margin: 0 }}>What new members see when they join: the rules to agree to, a few questions whose answers give them roles, and a welcome screen.</p>
    <label>Rules<textarea rows={5} value={rules} maxLength={2000} placeholder={"1. Be kind\n2. No spam"} onChange={(e) => setRules(e.target.value)} /></label>
    <label>Welcome message<textarea rows={2} value={welcome} maxLength={300} placeholder="Welcome! Start in #general." onChange={(e) => setWelcome(e.target.value)} /></label>
    <label>Suggested channels</label>
    {welcomeChannels.map((wc, i) => (
      <div key={i} style={{ display: "flex", gap: 6 }}>
        <input style={{ width: 60 }} value={wc.emoji ?? ""} placeholder="👋" onChange={(e) => setWelcomeChannels(welcomeChannels.map((x, j) => j === i ? { ...x, emoji: e.target.value } : x))} />
        <select value={wc.id} onChange={(e) => setWelcomeChannels(welcomeChannels.map((x, j) => j === i ? { ...x, id: e.target.value } : x))}>
          {textChannels.map((c) => <option key={c.id} value={c.id}>#{c.name}</option>)}
        </select>
        <input value={wc.description ?? ""} placeholder="What it's for" maxLength={60} onChange={(e) => setWelcomeChannels(welcomeChannels.map((x, j) => j === i ? { ...x, description: e.target.value } : x))} />
        <button className="icon-btn" onClick={() => setWelcomeChannels(welcomeChannels.filter((_, j) => j !== i))}>✕</button>
      </div>
    ))}
    {welcomeChannels.length < 5 && textChannels.length > 0 && <button className="btn small" style={{ justifySelf: "start" }} onClick={() => setWelcomeChannels([...welcomeChannels, { id: textChannels[0].id, description: "", emoji: "" }])}>＋ Add channel</button>}

    <label>Questions</label>
    {questions.map((q, i) => (
      <div key={i} className="card" style={{ display: "grid", gap: 8 }}>
        <div style={{ display: "flex", gap: 6 }}>
          <input value={q.question} placeholder="What do you play?" maxLength={100} onChange={(e) => setQ(i, { ...q, question: e.target.value })} />
          <button className="icon-btn" onClick={() => setQuestions(questions.filter((_, j) => j !== i))}>✕</button>
        </div>
        <label className="check"><input type="checkbox" checked={q.multiple} onChange={(e) => setQ(i, { ...q, multiple: e.target.checked })} /> People can pick more than one</label>
        {q.options.map((o, k) => (
          <div key={k} style={{ display: "flex", gap: 6, alignItems: "center", flexWrap: "wrap" }}>
            <input style={{ width: 60 }} value={o.emoji ?? ""} placeholder="⛏" onChange={(e) => setQ(i, { ...q, options: q.options.map((x, j) => j === k ? { ...x, emoji: e.target.value } : x) })} />
            <input style={{ flex: 1, minWidth: 140 }} value={o.label} placeholder="Answer" maxLength={50} onChange={(e) => setQ(i, { ...q, options: q.options.map((x, j) => j === k ? { ...x, label: e.target.value } : x) })} />
            <select multiple value={o.role_ids} style={{ minWidth: 150, height: 60 }}
              onChange={(e) => setQ(i, { ...q, options: q.options.map((x, j) => j === k ? { ...x, role_ids: [...e.target.selectedOptions].map((op) => op.value) } : x) })}>
              {roles.map((r) => <option key={r.id} value={r.id}>{r.name}</option>)}
            </select>
            <button className="icon-btn" onClick={() => setQ(i, { ...q, options: q.options.filter((_, j) => j !== k) })}>✕</button>
          </div>
        ))}
        {q.options.length < 10 && <button className="btn small" style={{ justifySelf: "start" }} onClick={() => setQ(i, { ...q, options: [...q.options, { label: "", emoji: null, role_ids: [] }] })}>＋ Add answer</button>}
        <span className="muted small">Pick roles for each answer (hold Ctrl or ⌘ to pick several).</span>
      </div>
    ))}
    {questions.length < 5 && <button className="btn small" style={{ justifySelf: "start" }} onClick={() => setQuestions([...questions, { question: "", multiple: false, options: [{ label: "", emoji: null, role_ids: [] }] }])}>＋ Add question</button>}
    <button className="btn primary" style={{ justifySelf: "start" }} onClick={() => void run(() => api(`/servers/${s.id}`, { method: "PATCH", body: {
      rules, welcome: { message: welcome, channels: welcomeChannels }, onboarding: questions.filter((q) => q.question.trim()).map((q) => ({ ...q, options: q.options.filter((o) => o.label.trim()) })),
    } }))}>Save onboarding</button>
  </>;
}

function BansTab({ detail, run, onError }: { detail: ServerDetail; run: Run; onError: Err }) {
  const [bans, setBans] = useState<{ uuid: string; reason: string | null; created_at: string; person?: Person }[] | null>(null);
  const load = () => api<{ bans: typeof bans }>(`/servers/${detail.server.id}/bans`).then((d) => setBans(d.bans)).catch((e) => onError(e.message));
  useEffect(() => { void load(); }, []);   // eslint-disable-line react-hooks/exhaustive-deps
  return <>
    <h2>Bans</h2>
    {bans === null ? <p className="muted">Loading…</p> : bans.length === 0 ? <p className="muted">Nobody is banned.</p> : bans.map((b) => (
      <div key={b.uuid} className="row">
        <Avatar p={b.person ?? { name: "?" }} size={32} />
        <div className="info"><b>{b.person?.name ?? b.uuid}</b><span>{b.reason || "No reason given"} · {new Date(b.created_at).toLocaleDateString()}</span></div>
        <button className="btn small" onClick={async () => { if (await run(() => api(`/servers/${detail.server.id}/bans?uuid=${b.uuid}`, { method: "DELETE" }))) void load(); }}>Unban</button>
      </div>
    ))}
  </>;
}

function BotsTab({ detail, run, onError }: { detail: ServerDetail; run: Run; onError: Err }) {
  const [list, setList] = useState<(Person & { official?: boolean })[]>([]);
  const [mine, setMine] = useState<Person[]>([]);
  const [q, setQ] = useState("");
  useEffect(() => {
    const t = setTimeout(() => {
      api<{ bots: (Person & { official?: boolean })[] }>(`/bots/directory?q=${encodeURIComponent(q)}`).then((d) => setList(d.bots)).catch((e) => onError(e.message));
    }, 250);
    return () => clearTimeout(t);
  }, [q]);   // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { api<{ bots: Person[] }>("/bots").then((d) => setMine(d.bots)).catch(() => {}); }, []);
  const inServer = new Set(detail.members.map((m) => m.uuid));
  const row = (b: Person & { official?: boolean }) => (
    <div key={b.uuid} className="row">
      <Avatar p={b} size={36} />
      <div className="info"><b>{b.name} <span className="bot-tag">BOT</span>{b.official && <span className="chip" style={{ marginLeft: 6 }}>Official</span>}</b><span>{b.bio ?? ""}</span></div>
      {inServer.has(b.uuid) ? <span className="muted small">Added</span>
        : <button className="btn small primary" onClick={() => void run(() => api(`/servers/${detail.server.id}/bots`, { body: { bot: b.uuid } }))}>Add</button>}
    </div>
  );
  return <>
    <h2>Bots</h2>
    <p className="muted small" style={{ margin: 0 }}>Bots join as members. Give them roles to let them do more (like giving roles or deleting messages).</p>
    {mine.length > 0 && <><div className="side-label">Your bots</div>{mine.map(row)}</>}
    <div className="side-label">Find bots</div>
    <input placeholder="Search public bots" value={q} onChange={(e) => setQ(e.target.value)} />
    {list.map(row)}
  </>;
}
