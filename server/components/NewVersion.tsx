"use client";
// When a new version of the website goes live, pages that are already open (the desktop and
// phone apps stay open for days) offer to reload: "Jace Social was updated".
import { useEffect, useState } from "react";

const MINE = process.env.NEXT_PUBLIC_BUILD ?? "dev";

export function NewVersion({ busy }: { busy: boolean }) {
  const [newer, setNewer] = useState(false);
  useEffect(() => {
    if (MINE === "dev") return;
    let last = 0;
    const look = async () => {
      if (Date.now() - last < 60_000) return;
      last = Date.now();
      try {
        const r = await fetch("/api/v1/version", { cache: "no-store" });
        const { build } = await r.json() as { build: string };
        if (build && build !== "dev" && build !== MINE) setNewer(true);
      } catch { /* offline */ }
    };
    const t = setInterval(look, 10 * 60_000);
    const onFocus = () => void look();
    window.addEventListener("focus", onFocus);
    document.addEventListener("visibilitychange", onFocus);
    void look();
    return () => { clearInterval(t); window.removeEventListener("focus", onFocus); document.removeEventListener("visibilitychange", onFocus); };
  }, []);
  if (!newer) return null;
  return (
    <div className="new-version" role="status">
      <span>✨ Jace Social was updated.</span>
      <button className="btn primary small" disabled={busy} title={busy ? "Finish your call first" : undefined}
        onClick={() => location.reload()}>{busy ? "Reload after your call" : "Reload"}</button>
      <button className="icon-btn" aria-label="Later" onClick={() => setNewer(false)}>✕</button>
    </div>
  );
}
