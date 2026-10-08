"use client";
import { useEffect, useRef, type ReactNode } from "react";
import { describeActivity, shortActivity, type Activity, type Person, type Status } from "@/lib/client";

export function Avatar({ p, size = 32, status }: {
  p: { name: string; avatar_url?: string | null } | null | undefined; size?: number; status?: Status | null;
}) {
  const name = p?.name ?? "?";
  const hue = [...name].reduce((h, c) => (h * 31 + c.charCodeAt(0)) % 360, 7);
  return (
    <span className="avatar" style={{ width: size, height: size, fontSize: size * 0.42, background: p?.avatar_url ? undefined : `hsl(${hue} 35% 32%)` }}>
      {p?.avatar_url
        // eslint-disable-next-line @next/next/no-img-element
        ? <img src={p.avatar_url} alt="" loading="lazy" />
        : name.slice(0, 1).toUpperCase()}
      {status && <span className={`dot ${status}`} />}
    </span>
  );
}

export function ActivityCard({ a }: { a: Activity | null | undefined }) {
  const d = describeActivity(a);
  if (!d) return null;
  return (
    <div className="activity">
      <div className="kind">{d.kind}</div>
      <b>{d.title}</b>
      {d.lines.map((l, i) => <div key={i} className="muted">{l}</div>)}
    </div>
  );
}

export function Modal({ children, onClose, wide }: { children: ReactNode; onClose: () => void; wide?: boolean }) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const k = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    window.addEventListener("keydown", k);
    ref.current?.querySelector<HTMLElement>("input, textarea, button")?.focus();
    return () => window.removeEventListener("keydown", k);
  }, [onClose]);
  return (
    <div className="backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div className={`modal${wide ? " wide" : ""}`} ref={ref} role="dialog" aria-modal="true">{children}</div>
    </div>
  );
}

export function PersonRow({ p, onClick, children }: { p: Person; onClick?: () => void; children?: ReactNode }) {
  return (
    <div className="row">
      <span onClick={onClick} style={{ cursor: onClick ? "pointer" : undefined }}><Avatar p={p} size={36} status={p.status} /></span>
      <div className="info" onClick={onClick} style={{ cursor: onClick ? "pointer" : undefined }}>
        <b>{p.name}</b>
        <span>{statusLine(p)}</span>
      </div>
      {children}
    </div>
  );
}

export const statusLine = (p: Person) => shortActivity(p);
