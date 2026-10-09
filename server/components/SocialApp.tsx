"use client";
// The Jace Social app: friends, direct messages, group chats and servers.
// The desktop app shows this same page (with a small bridge for Microsoft sign-in and notifications).
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  api, connectLive, desktop, desktopReady, getToken, inDesktop, joinAddress, setToken, statusLabel, type Group, type Me, type Person,
  type ServerDetail, type ServerItem,
} from "@/lib/client";
import { Calls } from "@/lib/calls";
import { CallPanel, useCall } from "./CallPanel";
import { Chat } from "./Chat";
import { MinecraftDevice } from "./MinecraftDevice";
import { ActivityCard, Avatar, PersonRow } from "./ui";
import {
  AddServerModal, NewGroupModal, ProfileModal, ServerSettingsModal, SettingsModal, StatusEditor,
} from "./Modals";

type FriendsData = { friends: Person[]; incoming: Person[]; outgoing: Person[] };
type View =
  | { kind: "home" }
  | { kind: "dm"; uuid: string }
  | { kind: "group"; id: string }
  | { kind: "server"; id: string; channel?: string };

export default function SocialApp({ inviteCode }: { inviteCode?: string }) {
  const [token, setTok] = useState<string | null>(null);
  const [ready, setReady] = useState(false);
  const [me, setMe] = useState<Me | null>(null);
  const [error, setError] = useState("");

  // Sign-in result from Jace comes back in the #fragment (never sent to servers or logs).
  useEffect(() => {
    const h = new URLSearchParams(location.hash.slice(1));
    if (h.get("token")) { setToken(h.get("token")); history.replaceState(null, "", location.pathname + location.search); }
    if (h.get("error")) setError(h.get("error")!);
    setTok(getToken());
    setReady(true);
  }, []);

  useEffect(() => {
    if (!token) return;
    api<Me>("/me").then(setMe).catch((e) => {
      if (e.status === 401) { setToken(null); setTok(null); } else setError(e.message);
    });
  }, [token]);

  if (!ready) return null;
  if (!token) return <SignIn error={error} inviteCode={inviteCode} />;
  if (!me) return <div className="center"><div className="muted">{error || "Loading Jace Social…"}</div></div>;
  return <Main me={me} setMe={setMe} inviteCode={inviteCode}
    signOut={async () => { await api("/auth/signout", { body: {} }).catch(() => {}); setToken(null); setTok(null); setMe(null); }} />;
}

function SignIn({ error, inviteCode }: { error: string; inviteCode?: string }) {
  const back = inviteCode ? `/invite/${inviteCode}` : "/app";
  const [app, setApp] = useState(false);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState("");
  const [device, setDevice] = useState(false);
  useEffect(() => { setApp(inDesktop()); }, []);
  async function minecraft() {
    setBusy(true); setProblem("");
    const d = await desktopReady();
    const r = d ? await d.signInMinecraft() : { error: "The desktop app isn't ready yet - try again" };
    setBusy(false);
    if (r.token) { setToken(r.token); location.reload(); } else setProblem(r.error ?? "Sign-in failed");
  }
  if (device) {
    return (
      <div className="center"><div className="signin">
        <MinecraftDevice<{ token: string }> mode="signin" onCancel={() => setDevice(false)}
          onDone={(r) => { setToken(r.token); location.href = back; }} />
      </div></div>
    );
  }
  return (
    <div className="center">
      <div className="signin">
        <h1>Jace Social</h1>
        <p className="muted">Friends, chat and servers - with Minecraft built in.</p>
        {(error || problem) && <p className="error">{problem || error}</p>}
        <a className="btn primary" style={{ display: "block", padding: 12, textDecoration: "none", margin: "20px 0 10px" }}
          href={`/api/v1/auth/jace?return_to=${encodeURIComponent(back)}`}>Sign in with Jace</a>
        {app
          ? <button className="btn" style={{ display: "block", width: "100%", padding: 12 }} disabled={busy} onClick={minecraft}>
              {busy ? "Signing in…" : "⛏ Sign in with Minecraft"}</button>
          : <button className="btn" style={{ display: "block", width: "100%", padding: 12 }} onClick={() => setDevice(true)}>
              ⛏ Sign in with Minecraft</button>}
      </div>
    </div>
  );
}

function Main({ me, setMe, signOut, inviteCode }: {
  me: Me; setMe: (m: Me) => void; signOut: () => void; inviteCode?: string;
}) {
  const [view, setView] = useState<View>({ kind: "home" });
  const [friends, setFriends] = useState<FriendsData>({ friends: [], incoming: [], outgoing: [] });
  const [groups, setGroups] = useState<Group[]>([]);
  const [servers, setServers] = useState<ServerItem[]>([]);
  const [detail, setDetail] = useState<ServerDetail | null>(null);
  const [reload, setReload] = useState(0);              // bump to reload the open chat
  const [profile, setProfile] = useState<string | null>(null);
  const [modal, setModal] = useState<"settings" | "group" | "server" | "serverSettings" | "status" | null>(inviteCode ? "server" : null);
  const [toast, setToast] = useState("");
  const [mobileMain, setMobileMain] = useState(false);
  const viewRef = useRef(view);
  viewRef.current = view;

  const err = useCallback((m: string) => { setToast(m); setTimeout(() => setToast(""), 5000); }, []);
  const meRef = useRef(me);
  meRef.current = me;
  const notifyUser = useCallback((title: string, body: string) => {
    if (meRef.current.status === "dnd" || document.hasFocus()) return;
    const d = desktop();
    if (d) d.notify(title, body);
    else if ("Notification" in window && Notification.permission === "granted") new Notification(title, { body });
  }, []);
  const [calls] = useState(() => new Calls(err, (name) => notifyUser(`${name} is calling`, "Open Jace Social to answer")));
  const call = useCall(calls);
  useEffect(() => {
    const bye = () => calls.hangUp();
    window.addEventListener("pagehide", bye);
    return () => { window.removeEventListener("pagehide", bye); bye(); };
  }, [calls]);
  const loadFriends = useCallback(() => api<FriendsData>("/friends").then(setFriends).catch((e) => err(e.message)), [err]);
  const loadGroups = useCallback(() => api<{ groups: Group[] }>("/groups").then((d) => setGroups(d.groups)).catch((e) => err(e.message)), [err]);
  const loadServers = useCallback(() => api<{ servers: ServerItem[] }>("/servers").then((d) => setServers(d.servers)).catch((e) => err(e.message)), [err]);
  const loadDetail = useCallback(async (id: string) => {
    try {
      const d = await api<ServerDetail>(`/servers/${id}`);
      setDetail(d);
      return d;
    } catch (e) { err((e as Error).message); setView({ kind: "home" }); return null; }
  }, [err]);

  useEffect(() => { void loadFriends(); void loadGroups(); void loadServers(); }, [loadFriends, loadGroups, loadServers]);

  useEffect(() => {
    if (desktop() || !("Notification" in window) || Notification.permission !== "default") return;
    const ask = () => { void Notification.requestPermission(); window.removeEventListener("click", ask); };
    window.addEventListener("click", ask);
    return () => window.removeEventListener("click", ask);
  }, []);

  // presence: "using the Jace Social app" every 2 minutes; offline when the page closes
  useEffect(() => {
    const beat = () => api("/presence", { body: { activity: { type: "app", app: desktop() ? "jace-social-desktop" : "jace-social-web" } } }).catch(() => {});
    void beat();
    const t = setInterval(beat, 120_000);
    const bye = () => { void api("/presence", { body: { activity: null, offline: true, app: true } }).catch(() => {}); };
    window.addEventListener("pagehide", bye);
    return () => { clearInterval(t); window.removeEventListener("pagehide", bye); };
  }, []);

  // live updates
  const people = useMemo(() => {
    const m: Record<string, Person> = {};
    for (const f of [...friends.friends, ...friends.incoming, ...friends.outgoing]) m[f.uuid] = f;
    for (const g of groups) for (const p of g.members) m[p.uuid] ??= p;
    for (const p of detail?.members ?? []) m[p.uuid] = p;
    return m;
  }, [friends, groups, detail]);
  const peopleRef = useRef(people);
  peopleRef.current = people;

  useEffect(() => {
    let presenceTimer: ReturnType<typeof setTimeout> | null = null;
    return connectLive(me.realtime, me.inbox, (event, p) => {
      const v = viewRef.current;
      if (event === "message") {
        if (v.kind === "dm" && v.uuid === p.from) setReload((n) => n + 1);
        else notifyUser(`${p.name ?? "A friend"}`, "Sent you a message");
        void loadFriends();
      } else if (event === "friends") {
        void loadFriends();
        if (p.kind === "request") notifyUser("Friend request", `${p.name} wants to be friends`);
      } else if (event === "presence") {
        if (!presenceTimer) presenceTimer = setTimeout(() => { presenceTimer = null; void loadFriends(); }, 1500);
      } else if (event === "channel") {
        const open = (v.kind === "group" && v.id === p.channel_id) || (v.kind === "server" && v.channel === p.channel_id);
        if (open) setReload((n) => n + 1);
        else if (!p.edited && !p.deleted && p.name) notifyUser(p.name, p.server_id ? "New message in a server" : "New message in a group");
        if (p.server_id) { void loadServers(); if (v.kind === "server" && v.id === p.server_id) void loadDetail(p.server_id); }
        else void loadGroups();
      } else if (event === "groups") {
        void loadGroups();
      } else if (event === "servers") {
        void loadServers();
        if (v.kind === "server" && v.id === p.server_id) {
          if (p.kind === "deleted" || p.kind === "removed") setView({ kind: "home" });
          else void loadDetail(p.server_id);
        }
      } else if (event === "call") {
        void calls.onSignal(p);
      }
    });
  }, [me.realtime, me.inbox, notifyUser, calls, loadFriends, loadGroups, loadServers, loadDetail]);

  // unread badge for the desktop app / tab title
  const unread = friends.friends.reduce((n, f) => n + (f.unread ?? 0), 0) + friends.incoming.length
    + groups.reduce((n, g) => n + g.unread, 0) + servers.reduce((n, s) => n + s.unread, 0);
  useEffect(() => {
    document.title = unread ? `(${unread}) Jace Social` : "Jace Social";
    desktop()?.setUnread(unread);
  }, [unread]);

  async function openServer(id: string, channel?: string) {
    const d = await loadDetail(id);
    if (!d) return;
    setView({ kind: "server", id, channel: channel ?? d.channels[0]?.id });
    setMobileMain(true);
  }

  const go = (v: View) => { setView(v); setMobileMain(v.kind !== "home" || true); };
  const dmPerson = view.kind === "dm" ? people[view.uuid] : undefined;
  const group = view.kind === "group" ? groups.find((g) => g.id === view.id) : undefined;
  const channel = view.kind === "server" ? detail?.channels.find((c) => c.id === view.channel) : undefined;
  const showRight = view.kind === "server" || view.kind === "group" || view.kind === "dm";

  return (
    <div className={`app${showRight ? "" : " no-right"}${mobileMain ? " show-main" : ""}`}>
      {/* server rail */}
      <nav className="rail" aria-label="Servers">
        <button className={`rail-item${view.kind !== "server" ? " active" : ""}`} title="Home"
          onClick={() => { setView({ kind: "home" }); setMobileMain(false); }}>
          <span style={{ fontSize: 22 }}>⛏</span>
          {friends.incoming.length > 0 && <span className="badge">{friends.incoming.length}</span>}
        </button>
        <div className="rail-sep" />
        {servers.map((s) => (
          <button key={s.id} className={`rail-item${view.kind === "server" && view.id === s.id ? " active" : ""}`} title={s.name}
            onClick={() => openServer(s.id)}>
            {s.icon_url ? <img src={s.icon_url} alt="" /> : s.name.split(/\s+/).map((w) => w[0]).join("").slice(0, 3)}
            {s.unread > 0 && <span className="badge">{s.unread > 99 ? "99+" : s.unread}</span>}
          </button>
        ))}
        <button className="rail-item rail-add" title="Create or join a server" onClick={() => setModal("server")}>+</button>
        <UpdateButton />
      </nav>

      {/* sidebar */}
      <aside className="sidebar">
        {view.kind === "server" && detail ? <>
          <div className="side-head">
            <span className="grow">{detail.server.name}</span>
            <button className="icon-btn" title="Server settings" onClick={() => setModal("serverSettings")}>⚙</button>
          </div>
          <div className="side-scroll">
            {detail.server.description && <p className="muted small" style={{ margin: "4px 8px 8px" }}>{detail.server.description}</p>}
            <div className="side-label">Text channels</div>
            {detail.channels.map((c) => (
              <button key={c.id} className={`side-item${view.channel === c.id ? " active" : ""}${c.unread ? " unread" : ""}`}
                onClick={() => { setView({ kind: "server", id: view.id, channel: c.id }); setMobileMain(true); void loadDetail(view.id); }}>
                <span className="hash">#</span><span className="name">{c.name}</span>
                {!!c.unread && view.channel !== c.id && <span className="badge">{c.unread}</span>}
              </button>
            ))}
          </div>
        </> : <>
          <div className="side-head"><span className="grow">Jace Social</span></div>
          <div className="side-scroll">
            <button className={`side-item${view.kind === "home" ? " active" : ""}`} onClick={() => { setView({ kind: "home" }); setMobileMain(true); }}>
              <span style={{ width: 32, textAlign: "center" }}>👥</span><span className="name">Friends</span>
              {friends.incoming.length > 0 && <span className="badge">{friends.incoming.length}</span>}
            </button>
            <div className="side-label">Group chats
              <button className="icon-btn" title="New group chat" onClick={() => setModal("group")}>＋</button></div>
            {groups.length === 0 && <p className="muted small" style={{ margin: "0 8px" }}>None yet</p>}
            {groups.map((g) => (
              <button key={g.id} className={`side-item${view.kind === "group" && view.id === g.id ? " active" : ""}${g.unread ? " unread" : ""}`}
                onClick={() => go({ kind: "group", id: g.id })}>
                <Avatar p={{ name: g.name, avatar_url: g.icon_url }} size={32} />
                <span className="name">{g.name}<span className="sub">{g.members.length} members</span></span>
                {g.unread > 0 && <span className="badge">{g.unread}</span>}
              </button>
            ))}
            <div className="side-label">Direct messages</div>
            {sortFriends(friends.friends).map((f) => (
              <button key={f.uuid} className={`side-item${view.kind === "dm" && view.uuid === f.uuid ? " active" : ""}${f.unread ? " unread" : ""}`}
                onClick={() => go({ kind: "dm", uuid: f.uuid })}>
                <Avatar p={f} size={32} status={f.status} />
                <span className="name">{f.name}<span className="sub">{shortLine(f)}</span></span>
                {!!f.unread && <span className="badge">{f.unread}</span>}
              </button>
            ))}
          </div>
        </>}
        <div className="me-panel">
          <Avatar p={me} size={34} status={me.status === "invisible" ? "offline" : me.status} />
          <div className="who" onClick={() => setModal("status")} title="Set status">
            <b>{me.name}</b><span>{me.custom_status ? `${me.status_emoji ?? ""} ${me.custom_status}` : statusLabel[me.status]}</span>
          </div>
          <button className="icon-btn" title="Settings" onClick={() => setModal("settings")}>⚙</button>
        </div>
      </aside>

      {/* main */}
      <main className="main">
        {view.kind === "home" && <FriendsHome data={friends} reload={loadFriends} onError={err}
          onOpen={(p) => setProfile(p.uuid)} onMessage={(p) => go({ kind: "dm", uuid: p.uuid })}
          onCall={Calls.supported() && call.state === "idle" ? (p) => void calls.call(p.uuid, p.name) : undefined} back={() => setMobileMain(false)} />}
        {view.kind === "dm" && dmPerson && <>
          <div className="main-head">
            <button className="icon-btn mobile-only" onClick={() => setMobileMain(false)}>←</button>
            <Avatar p={dmPerson} size={26} status={dmPerson.status} /><span className="title">{dmPerson.name}</span>
            <span className="topic">{shortLine(dmPerson)}</span>
            {Calls.supported() && <button className="icon-btn" title={`Voice call ${dmPerson.name}`} aria-label="Start a voice call"
              disabled={call.state !== "idle"} onClick={() => void calls.call(dmPerson.uuid, dmPerson.name)}>📞</button>}
          </div>
          <Chat target={{ kind: "dm", uuid: dmPerson.uuid, name: dmPerson.name }} me={me} people={people} canModerate={false}
            reloadKey={reload} onOpenProfile={setProfile} onError={err} placeholder={`Message ${dmPerson.name}`} />
        </>}
        {view.kind === "group" && group && <>
          <div className="main-head">
            <button className="icon-btn mobile-only" onClick={() => setMobileMain(false)}>←</button>
            <Avatar p={{ name: group.name, avatar_url: group.icon_url }} size={26} />
            <span className="title">{group.name}</span><span className="spacer" />
            <GroupMenu group={group} me={me} friends={friends.friends} onError={err} onChanged={loadGroups}
              onLeft={() => { setView({ kind: "home" }); void loadGroups(); }} />
          </div>
          <Chat target={{ kind: "channel", id: group.id, name: group.name, isServer: false }} me={me} people={people}
            canModerate={false} reloadKey={reload} onOpenProfile={setProfile} onError={err} placeholder={`Message ${group.name}`} />
        </>}
        {view.kind === "server" && channel && detail && <>
          <div className="main-head">
            <button className="icon-btn mobile-only" onClick={() => setMobileMain(false)}>←</button>
            <span className="hash">#</span><span className="title">{channel.name}</span>
            {channel.topic && <span className="topic">{channel.topic}</span>}
          </div>
          <Chat target={{ kind: "channel", id: channel.id, name: channel.name, isServer: true }} me={me} people={people}
            canModerate={detail.role !== "member"} reloadKey={reload} onOpenProfile={setProfile} onError={err}
            placeholder={`Message #${channel.name}`} />
        </>}
        {view.kind === "server" && !channel && <div className="empty">No channels yet</div>}
      </main>

      {/* right panel */}
      {showRight && (
        <aside className="right">
          {view.kind === "dm" && dmPerson && <ProfileCard p={dmPerson} onOpen={() => setProfile(dmPerson.uuid)} />}
          {view.kind === "group" && group && <MemberList members={group.members} onOpen={setProfile} />}
          {view.kind === "server" && detail && <MemberList members={detail.members} onOpen={setProfile} />}
        </aside>
      )}

      {profile && <ProfileModal uuid={profile} onClose={() => setProfile(null)} onError={err}
        onMessage={(p) => go({ kind: "dm", uuid: p.uuid })} onChanged={loadFriends} />}
      {modal === "settings" && <SettingsModal me={me} onClose={() => setModal(null)} onSaved={setMe} onSignOut={signOut} onError={err} />}
      {modal === "status" && <StatusModal me={me} onClose={() => setModal(null)} onSaved={setMe} onError={err} />}
      {modal === "group" && <NewGroupModal friends={friends.friends} onClose={() => setModal(null)} onError={err}
        onCreated={async (id) => { await loadGroups(); go({ kind: "group", id }); }} />}
      {modal === "server" && <AddServerModal initialCode={inviteCode} onClose={() => setModal(null)} onError={err}
        onDone={async (id) => { await loadServers(); await openServer(id); if (inviteCode) history.replaceState(null, "", "/app"); }} />}
      {modal === "serverSettings" && detail && <ServerSettingsModal detail={detail} me={me} onClose={() => setModal(null)} onError={err}
        onChanged={() => { void loadDetail(detail.server.id); void loadServers(); }}
        onLeft={() => { setView({ kind: "home" }); setDetail(null); void loadServers(); }} />}
      <CallPanel calls={calls} info={call} people={people} />
      {toast && <div role="alert" style={{ position: "fixed", bottom: 20, left: "50%", transform: "translateX(-50%)", background: "#3a2222",
        border: "1px solid #6b3433", padding: "10px 16px", borderRadius: 10, zIndex: 60, maxWidth: "90vw" }}>{toast}</div>}
    </div>
  );
}

/** In the desktop app: a green "update" button in the rail when a new version is out. */
function UpdateButton() {
  const [latest, setLatest] = useState<string | null>(null);
  useEffect(() => {
    let stop = false;
    const look = async () => {
      const d = await desktopReady();
      if (!d?.checkForUpdate || !d.applyUpdate) return;
      const r = await d.checkForUpdate();
      if (!stop) setLatest(r.newer && r.latest ? r.latest : null);
    };
    const first = setTimeout(look, 20_000);          // the app checks GitHub ~15 s after starting
    const t = setInterval(look, 3 * 3600_000);
    return () => { stop = true; clearTimeout(first); clearInterval(t); };
  }, []);
  if (!latest) return null;
  return (
    <button className="rail-item rail-update" title={`Update to Jace Social ${latest}`} aria-label={`Update to Jace Social ${latest}`}
      onClick={() => desktop()?.applyUpdate?.()}>⬆</button>
  );
}

const rank = (p: Person) => (p.online ? (p.activity && p.activity.type !== "app" ? 0 : 1) : 2);
function sortFriends(list: Person[]) {
  return [...list].sort((a, b) => (b.unread ?? 0) - (a.unread ?? 0) || rank(a) - rank(b) || a.name.localeCompare(b.name));
}
function shortLine(p: Person) {
  const s = p.custom_status ? `${p.status_emoji ?? ""} ${p.custom_status}`.trim() : "";
  if (s) return s;
  const a = p.activity;
  if (!p.online) return "Offline";
  if (a?.type === "playing") return `Playing Minecraft${a.version ? " " + a.version : ""}`;
  if (a?.type === "hosting") return "Hosting a world";
  if (a?.type === "launcher") return "In Jace Launcher";
  return statusLabel[p.status];
}

function StatusModal({ me, onClose, onSaved, onError }: { me: Me; onClose: () => void; onSaved: (m: Me) => void; onError: (m: string) => void }) {
  return (
    <div className="backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="modal"><div className="modal-body"><StatusEditor me={me} onSaved={onSaved} onError={onError} /></div>
        <div className="modal-foot"><button className="btn" onClick={onClose}>Done</button></div></div>
    </div>
  );
}

function ProfileCard({ p, onOpen }: { p: Person; onOpen: () => void }) {
  return (
    <div>
      <div className="profile-banner" style={{ background: p.accent_color ?? "#2a4a37", height: 60, borderRadius: 12 }} />
      <div style={{ marginTop: -30, paddingLeft: 12, cursor: "pointer" }} onClick={onOpen}><Avatar p={p} size={64} status={p.status} /></div>
      <div style={{ padding: "8px 6px", display: "grid", gap: 10 }}>
        <div><b style={{ fontSize: 18, cursor: "pointer" }} onClick={onOpen}>{p.name}</b>
          <div className="muted small">{[p.jace_name && `@${p.jace_name}`, p.minecraft_name && `⛏ ${p.minecraft_name}`, p.pronouns].filter(Boolean).join(" · ")}</div></div>
        {p.custom_status && <div className="small">{p.status_emoji} {p.custom_status}</div>}
        <ActivityCard a={p.activity} />
        {joinAddress(p) && <div className="small muted">Join from Jace Launcher or the mod: <code>{joinAddress(p)}</code></div>}
        {p.bio && <div className="small" style={{ whiteSpace: "pre-wrap" }}>{p.bio}</div>}
        {p.links.length > 0 && <div className="chips">{p.links.map((l) => <a key={l.url} className="chip" href={l.url} target="_blank" rel="noopener noreferrer nofollow">{l.label}</a>)}</div>}
      </div>
    </div>
  );
}

function MemberList({ members, onOpen }: { members: Person[]; onOpen: (uuid: string) => void }) {
  const online = members.filter((m) => m.online);
  const offline = members.filter((m) => !m.online);
  const section = (title: string, list: Person[]) => list.length > 0 && <>
    <div className="side-label">{title} — {list.length}</div>
    {list.sort((a, b) => a.name.localeCompare(b.name)).map((m) => (
      <button key={m.uuid} className="side-item" onClick={() => onOpen(m.uuid)} style={{ opacity: m.online ? 1 : 0.55 }}>
        <Avatar p={m} size={32} status={m.status} />
        <span className="name">{m.name}{m.role && m.role !== "member" && <span className="muted small"> {m.role === "owner" ? "👑" : "★"}</span>}
          <span className="sub">{shortLine(m)}</span></span>
      </button>
    ))}
  </>;
  return <>{section("Online", online)}{section("Offline", offline)}</>;
}

function GroupMenu({ group, me, friends, onError, onChanged, onLeft }: {
  group: Group; me: Me; friends: Person[]; onError: (m: string) => void; onChanged: () => void; onLeft: () => void;
}) {
  const [open, setOpen] = useState(false);
  const run = async (fn: () => Promise<unknown>) => { setOpen(false); try { await fn(); onChanged(); } catch (e) { onError((e as Error).message); } };
  const addable = friends.filter((f) => !group.members.some((m) => m.uuid === f.uuid));
  return (
    <span style={{ position: "relative" }}>
      <button className="icon-btn" onClick={() => setOpen(!open)} aria-label="Group options">⋯</button>
      {open && (
        <div className="menu" style={{ right: 0, top: 32 }} onMouseLeave={() => setOpen(false)}>
          <button onClick={() => { const n = prompt("Group name", group.name); if (n) void run(() => api(`/groups/${group.id}`, { method: "PATCH", body: { name: n } })); }}>✎ Rename</button>
          <label style={{ display: "flex", gap: 8, padding: "7px 10px", cursor: "pointer" }}>🖼 Change picture
            <input type="file" hidden accept="image/png,image/jpeg,image/gif,image/webp"
              onChange={(e) => { const f = e.target.files?.[0]; if (f) void run(() => api(`/groups/${group.id}/icon`, { raw: f })); }} /></label>
          {addable.slice(0, 20).map((f) => (
            <button key={f.uuid} onClick={() => run(() => api(`/groups/${group.id}/members`, { body: { uuid: f.uuid } }))}>＋ Add {f.name}</button>
          ))}
          {group.owner === me.uuid && group.members.filter((m) => m.uuid !== me.uuid).map((m) => (
            <button key={m.uuid} onClick={() => { if (confirm(`Remove ${m.name}?`)) void run(() => api(`/groups/${group.id}/members?uuid=${m.uuid}`, { method: "DELETE" })); }}>✕ Remove {m.name}</button>
          ))}
          <button style={{ color: "var(--red)" }} onClick={async () => {
            if (!confirm("Leave this group chat?")) return;
            setOpen(false);
            try { await api(`/groups/${group.id}/members?uuid=${me.uuid}`, { method: "DELETE" }); onLeft(); } catch (e) { onError((e as Error).message); }
          }}>⎋ Leave group</button>
        </div>
      )}
    </span>
  );
}

function FriendsHome({ data, reload, onError, onOpen, onMessage, onCall, back }: {
  data: FriendsData; reload: () => void; onError: (m: string) => void;
  onOpen: (p: Person) => void; onMessage: (p: Person) => void; onCall?: (p: Person) => void; back: () => void;
}) {
  const [tab, setTab] = useState<"online" | "all" | "pending" | "add">("online");
  const [name, setName] = useState("");
  const [note, setNote] = useState("");
  const [results, setResults] = useState<Person[]>([]);
  const list = tab === "online" ? data.friends.filter((f) => f.online) : data.friends;

  async function add(body: { name?: string; uuid?: string }) {
    setNote("");
    try {
      const r = await api<{ status: string; friend: Person }>("/friends", { body });
      setNote(r.status === "accepted" ? `You and ${r.friend.name} are now friends!` : `Friend request sent to ${r.friend.name}`);
      setName(""); setResults([]); reload();
    } catch (e) { onError((e as Error).message); }
  }
  async function search(q: string) {
    setName(q);
    if (q.trim().length < 2) { setResults([]); return; }
    try { setResults((await api<{ users: Person[] }>(`/users?q=${encodeURIComponent(q.trim())}`)).users); } catch { setResults([]); }
  }
  const act = async (fn: () => Promise<unknown>) => { try { await fn(); reload(); } catch (e) { onError((e as Error).message); } };

  return <>
    <div className="main-head">
      <button className="icon-btn mobile-only" onClick={back}>←</button>
      <span className="title">👥 Friends</span>
      <div className="tabs">
        <button className={`tab${tab === "online" ? " active" : ""}`} onClick={() => setTab("online")}>Online</button>
        <button className={`tab${tab === "all" ? " active" : ""}`} onClick={() => setTab("all")}>All</button>
        <button className={`tab${tab === "pending" ? " active" : ""}`} onClick={() => setTab("pending")}>
          Pending{data.incoming.length > 0 && <> <span className="badge">{data.incoming.length}</span></>}</button>
        <button className={`tab add${tab === "add" ? " active" : ""}`} onClick={() => setTab("add")}>Add friend</button>
      </div>
    </div>
    <div className="list">
      {tab === "add" && <>
        <h3>Add a friend</h3>
        <p className="muted small">Type a Minecraft username, or a Jace username (start with @ to only search Jace).</p>
        <form style={{ display: "flex", gap: 8 }} onSubmit={(e) => { e.preventDefault(); if (name.trim()) void add({ name: name.trim() }); }}>
          <input placeholder="Username" value={name} maxLength={33} onChange={(e) => search(e.target.value)} />
          <button className="btn primary" disabled={!name.trim()}>Send request</button>
        </form>
        {note && <p className="small" style={{ color: "var(--green)" }}>{note}</p>}
        {results.map((p) => (
          <PersonRow key={p.uuid} p={p} onClick={() => onOpen(p)}><button className="btn small" onClick={() => add({ uuid: p.uuid })}>Add</button></PersonRow>
        ))}
      </>}
      {(tab === "online" || tab === "all") && <>
        <div className="side-label">{tab === "online" ? "Online" : "All friends"} — {list.length}</div>
        {list.length === 0 && <p className="muted">{tab === "online" ? "No friends are online right now." : "No friends yet - add some!"}</p>}
        {sortFriends(list).map((f) => (
          <PersonRow key={f.uuid} p={f} onClick={() => onOpen(f)}>
            {onCall && f.online && <button className="btn small" title={`Voice call ${f.name}`} onClick={() => onCall(f)}>📞</button>}
            <button className="btn small" onClick={() => onMessage(f)}>Message</button>
          </PersonRow>
        ))}
      </>}
      {tab === "pending" && <>
        <div className="side-label">Incoming — {data.incoming.length}</div>
        {data.incoming.map((p) => (
          <PersonRow key={p.uuid} p={p} onClick={() => onOpen(p)}>
            <button className="btn primary small" onClick={() => act(() => api("/friends/respond", { body: { uuid: p.uuid, accept: true } }))}>Accept</button>
            <button className="btn small" onClick={() => act(() => api("/friends/respond", { body: { uuid: p.uuid, accept: false } }))}>Decline</button>
          </PersonRow>
        ))}
        <div className="side-label">Sent — {data.outgoing.length}</div>
        {data.outgoing.map((p) => (
          <PersonRow key={p.uuid} p={p} onClick={() => onOpen(p)}>
            <button className="btn small" onClick={() => act(() => api(`/friends?uuid=${p.uuid}`, { method: "DELETE" }))}>Cancel</button>
          </PersonRow>
        ))}
      </>}
    </div>
  </>;
}
