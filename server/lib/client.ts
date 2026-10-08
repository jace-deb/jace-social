"use client";
// Browser side of Jace Social: API calls with the session token, live updates, helpers.
import { createClient, type RealtimeChannel } from "@supabase/supabase-js";

const TOKEN = "jace_social_token";

export function getToken(): string | null {
  try { return localStorage.getItem(TOKEN); } catch { return null; }
}
export function setToken(t: string | null) {
  try { if (t) localStorage.setItem(TOKEN, t); else localStorage.removeItem(TOKEN); } catch { /* private mode */ }
}

export class ApiError extends Error {
  constructor(public status: number, message: string) { super(message); }
}

export async function api<T = any>(path: string, opts: { method?: string; body?: unknown; raw?: Blob } = {}): Promise<T> {
  const headers: Record<string, string> = {};
  const t = getToken();
  if (t) headers.Authorization = `Bearer ${t}`;
  let body: BodyInit | undefined;
  if (opts.raw) { body = opts.raw; headers["Content-Type"] = opts.raw.type; }
  else if (opts.body !== undefined) { body = JSON.stringify(opts.body); headers["Content-Type"] = "application/json"; }
  let r: Response;
  try {
    r = await fetch(`/api/v1${path}`, { method: opts.method ?? (body ? "POST" : "GET"), headers, body, keepalive: opts.method === "POST" && !opts.raw });
  } catch {
    throw new ApiError(0, "Can't reach Jace Social - check your internet connection");
  }
  const d = await r.json().catch(() => ({}));
  if (!r.ok) throw new ApiError(r.status, d.error || `Error ${r.status}`);
  return d as T;
}

/** Live notifications on the player's private channel (same as Jace Launcher and the mod use). */
export function connectLive(realtime: { url: string; key: string }, inbox: string,
                            onEvent: (event: string, payload: any) => void): () => void {
  const sb = createClient(realtime.url, realtime.key, { auth: { persistSession: false } });
  const ch: RealtimeChannel = sb.channel(inbox)
    .on("broadcast", { event: "*" }, (m) => onEvent(m.event, m.payload ?? {}))
    .subscribe();
  return () => { void sb.removeChannel(ch); };
}

// --- shapes from the API ---------------------------------------------------------
export type Status = "online" | "idle" | "dnd" | "invisible" | "offline";
export type Activity = {
  type: "launcher" | "playing" | "hosting" | "app";
  instance?: string; version?: string; loader?: string; modpack?: string; server?: string; world?: string;
  address?: string; details?: string; state?: string; started_at?: string; app?: string;
};
export type Person = {
  uuid: string; name: string; online: boolean; status: Status; uses_jace: boolean;
  activity: Activity | null; last_seen: string | null; custom_status: string | null; status_emoji: string | null;
  minecraft_name: string | null; jace_name: string | null; avatar_url: string | null;
  bio: string | null; pronouns: string | null; accent_color: string | null; links: { label: string; url: string }[];
  unread?: number; role?: "owner" | "admin" | "member";
};
export type Me = Person & {
  display_name: string | null; minecraft_linked: boolean; jace_linked: boolean; inbox: string;
  realtime: { url: string; key: string };
};
export type Group = { id: string; name: string; icon_url: string | null; owner: string; members: Person[]; unread: number; last_message_at: string };
export type ServerItem = { id: string; name: string; icon_url: string | null; role: string; unread: number };
export type Channel = { id: string; server_id: string | null; name: string; topic: string | null; unread?: number; position: number };
export type ServerDetail = {
  server: { id: string; name: string; icon_url: string | null; description: string | null; owner: string; invite_code: string | null };
  role: "owner" | "admin" | "member"; channels: Channel[]; members: Person[];
};
export type Message = {
  id: number; sender: string | null; body: string; created_at: string; edited_at?: string | null; kind?: "text" | "system";
  read_at?: string | null; recipient?: string;
};

// --- formatting -------------------------------------------------------------------
export const statusLabel: Record<Status, string> = {
  online: "Online", idle: "Idle", dnd: "Do Not Disturb", invisible: "Invisible", offline: "Offline",
};

export function since(iso?: string | null) {
  if (!iso) return "";
  const s = Math.max(0, (Date.now() - new Date(iso).getTime()) / 1000);
  if (s < 60) return "just now";
  if (s < 3600) return `${Math.floor(s / 60)} min`;
  if (s < 86400) return `${Math.floor(s / 3600)} h ${Math.floor((s % 3600) / 60)} min`;
  return `${Math.floor(s / 86400)} days`;
}

export function timeLabel(iso: string) {
  const d = new Date(iso);
  const today = new Date();
  const t = d.toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
  if (d.toDateString() === today.toDateString()) return `Today at ${t}`;
  const y = new Date(today); y.setDate(today.getDate() - 1);
  if (d.toDateString() === y.toDateString()) return `Yesterday at ${t}`;
  return `${d.toLocaleDateString()} ${t}`;
}

/** Rich presence as {kind, lines} for the activity card, or null. */
export function describeActivity(a: Activity | null | undefined): { kind: string; title: string; lines: string[] } | null {
  if (!a) return null;
  const lines: string[] = [];
  const mc = ["Minecraft", a.version, a.loader && a.loader !== "vanilla" ? `(${a.loader})` : ""].filter(Boolean).join(" ");
  if (a.type === "hosting") {
    lines.push(a.world ? `Hosting "${a.world}"` : "Hosting a world");
    if (a.address) lines.push("Friends can join");
  } else if (a.type === "playing") {
    if (a.server) lines.push(`On ${a.server}`);
    else if (a.world) lines.push(`In "${a.world}"`);
    if (a.modpack) lines.push(a.modpack);
    else if (a.instance) lines.push(a.instance);
  } else if (a.type === "launcher") {
    lines.push("In Jace Launcher");
  } else {
    return null;                                  // just using the Jace Social app: no card
  }
  if (a.details) lines.push(a.details);
  if (a.state) lines.push(a.state);
  if (a.started_at) lines.push(`for ${since(a.started_at)}`);
  return { kind: a.type === "launcher" ? "Jace Launcher" : "Playing Minecraft", title: a.type === "launcher" ? "Jace Launcher" : mc, lines };
}

export function shortActivity(p: Person): string {
  if (p.custom_status) return `${p.status_emoji ? p.status_emoji + " " : ""}${p.custom_status}`;
  const a = p.activity;
  if (!p.online) return p.last_seen ? `Last seen ${since(p.last_seen)} ago` : "Offline";
  if (!a || a.type === "app") return statusLabel[p.status];
  if (a.type === "hosting") return `Hosting ${a.world ? `"${a.world}"` : "a world"}`;
  if (a.type === "playing") return `Playing Minecraft${a.version ? " " + a.version : ""}${a.server ? " on " + a.server : ""}`;
  return "In Jace Launcher";
}

export function joinAddress(p: Person): string | null {
  const a = p.activity;
  if (!p.online || !a) return null;
  if (a.type === "hosting") return a.address ?? null;
  if (a.type === "playing") return a.server ?? null;
  return null;
}

/** The desktop app adds this bridge (see app/ in the repo); the web version doesn't have it. */
export type DesktopBridge = {
  signInMinecraft(): Promise<{ token?: string; error?: string }>;
  linkMinecraft(token: string): Promise<{ ok?: boolean; error?: string }>;
  notify(title: string, body: string): void;
  setUnread(count: number): void;
  version: string;
};
export function desktop(): DesktopBridge | null {
  return (globalThis as unknown as { jaceDesktop?: DesktopBridge }).jaceDesktop ?? null;
}

/** True inside the desktop app (even before its bridge has finished connecting). */
export function inDesktop(): boolean {
  return !!(globalThis as unknown as { jaceDesktopPending?: boolean }).jaceDesktopPending;
}

/** Wait for the desktop bridge (it connects a moment after the page loads). */
export function desktopReady(): Promise<DesktopBridge | null> {
  if (desktop() || !inDesktop()) return Promise.resolve(desktop());
  return new Promise((ok) => {
    const done = () => ok(desktop());
    window.addEventListener("jacedesktop", done, { once: true });
    setTimeout(done, 5000);
  });
}
