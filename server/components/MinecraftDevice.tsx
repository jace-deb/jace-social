"use client";
// Minecraft sign-in (or linking) in the browser: show Microsoft's device code, then wait
// while the player enters it at microsoft.com/link (see lib/minecraft.ts on the server).
import { useEffect, useRef, useState } from "react";
import { api } from "@/lib/client";

type Start = { user_code: string; verification_uri: string; interval: number; expires_at: string; ticket: string };

export function MinecraftDevice<T>({ mode, onDone, onCancel }: {
  mode: "signin" | "link"; onDone: (result: T) => void; onCancel: () => void;
}) {
  const [start, setStart] = useState<Start | null>(null);
  const [error, setError] = useState("");
  const [copied, setCopied] = useState(false);
  const alive = useRef(true);

  useEffect(() => {
    alive.current = true;
    const base = mode === "signin" ? "/auth/minecraft" : "/link/minecraft/device";
    (async () => {
      try {
        const s = await api<Start>(base, { body: {} });
        if (!alive.current) return;
        setStart(s);
        let wait = Math.max(3, s.interval) * 1000;
        while (alive.current && Date.now() < new Date(s.expires_at).getTime()) {
          await new Promise((ok) => setTimeout(ok, wait));
          if (!alive.current) return;
          try {
            const r = await api<T & { pending?: boolean }>(`${base}/poll`, { body: { ticket: s.ticket } });
            if (!r.pending) { onDone(r); return; }
          } catch (e) {
            const status = (e as { status?: number }).status;
            if (status && status < 500) throw e;
            wait = Math.min(wait * 2, 30_000);          // Microsoft or the network hiccuped: back off
          }
        }
        if (alive.current) setError("The code expired - start again");
      } catch (e) {
        if (alive.current) setError((e as Error).message);
      }
    })();
    return () => { alive.current = false; };
  }, [mode]);                                          // eslint-disable-line react-hooks/exhaustive-deps

  const link = start ? `${start.verification_uri}?otc=${encodeURIComponent(start.user_code)}` : "";
  return (
    <div className="mc-device">
      <h3 style={{ margin: "0 0 6px" }}>{mode === "signin" ? "Sign in with Minecraft" : "Link Minecraft"}</h3>
      {error ? <p className="error">{error}</p> : !start ? <p className="muted">Getting a code from Microsoft…</p> : <>
        <p className="muted small" style={{ margin: "0 0 10px" }}>
          Open Microsoft's sign-in page, enter this code, and sign in with the account that owns Minecraft.
        </p>
        <div className="mc-code" aria-label="Your code">{start.user_code}</div>
        <div style={{ display: "flex", gap: 8, justifyContent: "center", margin: "12px 0" }}>
          <a className="btn primary" href={link} target="_blank" rel="noopener noreferrer">Open microsoft.com/link</a>
          <button className="btn" onClick={() => { void navigator.clipboard?.writeText(start.user_code); setCopied(true); }}>
            {copied ? "Copied" : "Copy code"}</button>
        </div>
        <p className="muted small" style={{ margin: 0 }}>Waiting for you to finish… This page updates by itself.</p>
      </>}
      <button className="btn small" style={{ marginTop: 12 }} onClick={() => { alive.current = false; onCancel(); }}>Cancel</button>
    </div>
  );
}
