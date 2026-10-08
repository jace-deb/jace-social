"use client";
// The floating voice call panel: ringing, calling, and in a call.
import { useEffect, useState } from "react";
import type { Person } from "@/lib/client";
import type { CallInfo, Calls } from "@/lib/calls";
import { Avatar } from "./ui";

export function useCall(calls: Calls): CallInfo {
  const [info, setInfo] = useState(calls.info);
  useEffect(() => calls.subscribe(setInfo), [calls]);
  return info;
}

export function CallPanel({ calls, info, people }: { calls: Calls; info: CallInfo; people: Record<string, Person> }) {
  const [, tick] = useState(0);
  useEffect(() => {
    if (info.state !== "in-call") return;
    const t = setInterval(() => tick((n) => n + 1), 1000);
    return () => clearInterval(t);
  }, [info.state]);
  if (info.state === "idle") return null;

  const p = people[info.peer] ?? { name: info.peerName };
  const secs = info.startedAt ? Math.floor((Date.now() - info.startedAt) / 1000) : 0;
  const line = info.state === "ringing" ? "Incoming voice call"
    : info.state === "calling" ? "Calling…"
    : `${Math.floor(secs / 60)}:${String(secs % 60).padStart(2, "0")}${info.noMic ? " · no microphone" : ""}`;

  return (
    <div className="call-panel" role="dialog" aria-label="Voice call">
      <Avatar p={p} size={44} />
      <div className="call-who"><b>{p.name}</b><span>{line}</span></div>
      {info.state === "ringing" ? <>
        <button className="btn primary small" onClick={() => void calls.answer()}>Answer</button>
        <button className="btn danger small" onClick={() => calls.hangUp()}>Decline</button>
      </> : <>
        {info.state === "in-call" && !info.noMic &&
          <button className="btn small" onClick={() => calls.toggleMute()} aria-pressed={info.muted}>{info.muted ? "Unmute" : "Mute"}</button>}
        <button className="btn danger small" onClick={() => calls.hangUp()}>{info.state === "calling" ? "Cancel" : "Hang up"}</button>
      </>}
    </div>
  );
}
