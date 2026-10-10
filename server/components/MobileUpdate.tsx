"use client";
// Updates for the Android / iPhone app (mobile/ in the repo), like the desktop app's: check a
// few seconds after opening and every few hours, ask "Update now / Later" once per version
// (with what's new), keep an Update button in the sidebar, and a setting to turn it off.
// The app itself is a shell around this website, so only its own version needs updating.
import { useEffect, useState } from "react";
import { mobileApp } from "@/lib/client";

type Latest = { version: string; notes: string; page: string; downloads: { id: string; url: string }[] };
const AUTO = "jace.mobileAutoUpdate", ASKED = "jace.mobileAskedAbout";
const store = {
  get: (k: string) => { try { return localStorage.getItem(k); } catch { return null; } },
  set: (k: string, v: string) => { try { localStorage.setItem(k, v); } catch { /* private mode */ } },
};

export function newerVersion(latest: string, current: string) {
  const a = latest.split(".").map(Number), b = current.split(".").map(Number);
  for (let i = 0; i < 3; i++) if ((a[i] || 0) !== (b[i] || 0)) return (a[i] || 0) > (b[i] || 0);
  return false;
}

export async function checkMobileUpdate(): Promise<{ latest: Latest | null; error?: string }> {
  const app = mobileApp();
  if (!app) return { latest: null };
  try {
    const r = await fetch("/api/v1/releases/mobile");
    const rel = await r.json() as Latest & { error?: string };
    if (!r.ok) return { latest: null, error: rel.error || "Couldn't check for updates - try again later" };
    return { latest: newerVersion(rel.version, app.version) ? rel : null };
  } catch {
    return { latest: null, error: "Couldn't check for updates - try again later" };
  }
}

/** Android: the new APK (the system installs it over this one); iPhone: the release page. */
export function openMobileUpdate(l: Latest) {
  const app = mobileApp();
  const apk = l.downloads.find((d) => d.id === "android")?.url;
  location.href = app?.platform === "android" && apk ? apk : l.page;      // the app opens it in the browser
}

export const mobileAutoUpdate = () => store.get(AUTO) !== "false";
export const setMobileAutoUpdate = (on: boolean) => store.set(AUTO, on ? "true" : "false");

/** The sidebar button and the "Update now?" prompt. */
export function MobileUpdate() {
  const [latest, setLatest] = useState<Latest | null>(null);
  const [asking, setAsking] = useState(false);
  useEffect(() => {
    if (!mobileApp() || !mobileAutoUpdate()) return;
    let stop = false;
    const look = async () => {
      const { latest: l } = await checkMobileUpdate();
      if (stop) return;
      setLatest(l);
      if (l && store.get(ASKED) !== l.version) { store.set(ASKED, l.version); setAsking(true); }
    };
    const first = setTimeout(look, 3_000);
    const t = setInterval(look, 3 * 3600_000);
    return () => { stop = true; clearTimeout(first); clearInterval(t); };
  }, []);
  if (!latest) return null;
  const app = mobileApp()!;
  return <>
    <button className="rail-item rail-update" title={`Update to Jace Social ${latest.version}`} aria-label={`Update to Jace Social ${latest.version}`}
      onClick={() => setAsking(true)}>⬆<small>Update</small></button>
    {asking && (
      <div className="backdrop" onClick={() => setAsking(false)}>
        <div className="modal modal-body" role="dialog" aria-label="Update Jace Social" onClick={(e) => e.stopPropagation()} style={{ maxWidth: 420 }}>
          <h2 style={{ marginTop: 0 }}>Update to Jace Social {latest.version}?</h2>
          <p className="muted">You have {app.version}. {app.platform === "android"
            ? "The new version downloads; open it to install it over this one. You stay signed in."
            : "Get the new version from the release page."}</p>
          {latest.notes && <pre className="update-notes">{latest.notes.trim()}</pre>}
          <div style={{ display: "flex", gap: 8, justifyContent: "flex-end" }}>
            <button className="btn" onClick={() => setAsking(false)}>Later</button>
            <button className="btn primary" onClick={() => { setAsking(false); openMobileUpdate(latest); }}>Update now</button>
          </div>
        </div>
      </div>
    )}
  </>;
}

/** Settings: the phone app's version, Check for updates, and the startup check switch. */
export function MobileVersion() {
  const [state, setState] = useState<{ busy?: boolean; text?: string; latest?: Latest }>({});
  const [auto, setAuto] = useState(true);
  useEffect(() => setAuto(mobileAutoUpdate()), []);
  const app = mobileApp();
  if (!app) return null;
  async function check() {
    setState({ busy: true });
    const r = await checkMobileUpdate();
    setState(r.error ? { text: r.error } : r.latest ? { latest: r.latest } : { text: "You're up to date" });
  }
  return (
    <div className="muted small" style={{ padding: "12px 8px 0", display: "grid", gap: 6 }}>
      <span>Phone app {app.version}</span>
      {state.latest
        ? <button className="btn primary small" onClick={() => openMobileUpdate(state.latest!)}>Update to {state.latest.version}</button>
        : <button className="btn small" disabled={state.busy} onClick={check}>{state.busy ? "Checking…" : "Check for updates"}</button>}
      {state.text && <span>{state.text}</span>}
      <label style={{ display: "flex", gap: 6, alignItems: "center" }}>
        <input type="checkbox" checked={auto} onChange={(e) => { setAuto(e.target.checked); setMobileAutoUpdate(e.target.checked); }} />
        Check for updates on startup
      </label>
    </div>
  );
}
