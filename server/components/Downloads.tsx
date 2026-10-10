"use client";
// The homepage's download buttons: the one for your computer is highlighted.
import { useEffect, useState } from "react";

export type Download = { id: string; url: string; label: string; note: string; mb: number };

function guess(): string | null {
  const ua = navigator.userAgent;
  if (/Android/i.test(ua)) return "android";
  if (/Windows/i.test(ua)) return "windows";
  if (/Mac OS X|Macintosh/i.test(ua)) return "mac-arm";
  if (/Linux|X11/i.test(ua) && !/Android/i.test(ua)) return "linux";
  return null;
}

export function Downloads({ list }: { list: Download[] }) {
  const [mine, setMine] = useState<string | null>(null);
  useEffect(() => { setMine(guess()); }, []);
  return <>
    {list.map((d) => (
      <a key={d.id} className={`btn${d.id === mine ? " mine" : ""}`} href={d.url}>
        ⬇ {d.label} <small>{d.note} · {d.mb} MB</small>
      </a>
    ))}
  </>;
}
