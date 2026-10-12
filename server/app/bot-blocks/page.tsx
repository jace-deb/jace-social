"use client";
// The block bot editor on its own, for the native phone apps (docs/native-apps.md): they open
// /bot-blocks?bot=<id>#token=<session token> in a web view. The token comes in the #fragment,
// so it's never sent to a server or logged. Leaving the editor goes to jacesocial://close,
// which the app catches to close the web view.
import { useEffect, useState } from "react";
import { BotEditor, type Bot } from "@/components/Bots";
import { api, getToken, setToken } from "@/lib/client";

export default function BotBlocksPage() {
  const [bot, setBot] = useState<Bot | null>(null);
  const [error, setError] = useState("");
  useEffect(() => {
    const h = new URLSearchParams(location.hash.slice(1));
    if (h.get("token")) { setToken(h.get("token")); history.replaceState(null, "", location.pathname + location.search); }
    const id = new URLSearchParams(location.search).get("bot");
    if (!id || !getToken()) { setError("Open this from the Jace Social app"); return; }
    api<{ bot: Bot }>(`/bots/${id}`).then((d) => setBot(d.bot)).catch((e) => setError(e.message));
  }, []);
  if (error) return <p className="muted" style={{ padding: 24 }}>{error}</p>;
  if (!bot) return <p className="muted" style={{ padding: 24 }}>Loading…</p>;
  return <BotEditor bot={bot} onClose={() => { location.href = "jacesocial://close"; }} onError={setError} />;
}
