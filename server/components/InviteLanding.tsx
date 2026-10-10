"use client";
// The invite page: the server, and a choice of the app (desktop or phone) or the browser.
import { useEffect, useState } from "react";
import { inDesktop, inMobileApp, onPhone } from "@/lib/client";
import { Avatar } from "./ui";

type Server = { id: string; name: string; icon_url: string | null; banner_url: string | null; description: string | null;
  accent_color: string | null; invites_paused: boolean; members: number; online: number } | null;

export default function InviteLanding({ code, server }: { code: string; server: Server }) {
  const [tried, setTried] = useState(false);
  const [phone, setPhone] = useState(false);
  useEffect(() => setPhone(onPhone()), []);
  useEffect(() => {
    // inside the desktop or phone app already: go straight to joining
    if (inDesktop() || inMobileApp()) location.replace(`/invite/${encodeURIComponent(code)}`);
  }, [code]);
  if (!server) {
    return (
      <main className="center"><div className="signin invite-card">
        <div style={{ fontSize: 44 }}>🔗</div>
        <h1 style={{ color: "var(--text)" }}>Invite invalid</h1>
        <p className="muted">This invite may have expired, or you might not have permission to join.</p>
        <a className="btn primary invite-btn" href="/app">Open Jace Social</a>
      </div></main>
    );
  }
  const openApp = () => {
    setTried(true);
    location.href = `jacesocial://invite/${encodeURIComponent(code)}`;
  };
  return (
    <main className="center invite-page" style={server.banner_url ? { background: `linear-gradient(rgba(0,0,0,.55), rgba(0,0,0,.75)), center / cover url(${server.banner_url})` } : undefined}>
      <div className="signin invite-card">
        <div className="muted small">You've been invited to join</div>
        <Avatar p={{ name: server.name, avatar_url: server.icon_url }} size={80} />
        <h1 style={{ color: "var(--text)", margin: 0 }}>{server.name}</h1>
        {server.description && <p className="muted" style={{ margin: 0 }}>{server.description}</p>}
        <div className="invite-counts">
          <span><i className="dot online" /> {server.online} online</span>
          <span><i className="dot offline" /> {server.members} member{server.members === 1 ? "" : "s"}</span>
        </div>
        {server.invites_paused ? <p className="error">This server isn't taking new members right now.</p> : <>
          <button className="btn primary invite-btn" onClick={openApp}>{phone ? "Open in the app" : "Open in the desktop app"}</button>
          <a className="btn invite-btn" href={`/invite/${encodeURIComponent(code)}`}>Continue in the browser</a>
          {tried && <p className="muted small" style={{ margin: 0 }}>
            Nothing happened? <a href="/#download">Get the {phone ? "app" : "desktop app"}</a>, or continue in the browser.</p>}
        </>}
      </div>
    </main>
  );
}
