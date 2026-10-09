"use client";
// Message text: Discord-style formatting (**bold**, *italics*, __underline__, ~~strike~~,
// ||spoilers||, `code`, ```blocks```, > quotes, # headings, [text](links)), clickable links
// that warn before leaving Jace Social, and mentions. Built as React elements: message text
// is never turned into HTML.
import { createContext, useContext, useState, type ReactNode } from "react";
import type { Person, Role } from "@/lib/client";
import { Modal } from "./ui";

// ---------------------------------------------------------------------------- links

type LinkCtx = { open: (url: string, masked?: string) => void };
const LinkContext = createContext<LinkCtx>({ open: (u) => window.open(u, "_blank", "noopener,noreferrer") });

const OWN_HOSTS = ["jace-social.vercel.app", "jace-deb.github.io", "jace-store-deb.vercel.app"];

function hostOf(url: string) {
  try { return new URL(url).hostname.toLowerCase().replace(/^www\./, ""); } catch { return ""; }
}

/** Wraps the app: clicking a link outside trusted sites first shows where it goes. */
export function LinkGuard({ warn, trusted, onTrust, children }: {
  warn: boolean; trusted: string[]; onTrust: (domain: string) => void; children: ReactNode;
}) {
  const [pending, setPending] = useState<{ url: string; masked?: string } | null>(null);
  const [trust, setTrust] = useState(false);
  const go = (url: string) => {
    if (typeof location !== "undefined" && url.startsWith(location.origin)) { location.href = url; return; }
    window.open(url, "_blank", "noopener,noreferrer");
  };
  const open = (url: string, masked?: string) => {
    const host = hostOf(url);
    if (!host) return;
    const safe = OWN_HOSTS.includes(host) || trusted.some((d) => host === d || host.endsWith("." + d));
    if (!warn || (safe && !masked)) return go(url);
    setTrust(false);
    setPending({ url, masked });
  };
  return (
    <LinkContext.Provider value={{ open }}>
      {children}
      {pending && (
        <Modal onClose={() => setPending(null)}>
          <div className="modal-body">
            <h2>Leaving Jace Social</h2>
            <p className="muted" style={{ margin: 0 }}>This link goes to a website outside Jace Social. Only open it if you trust it.</p>
            {pending.masked && <p className="small" style={{ margin: 0, color: "var(--yellow)" }}>
              The link says “{pending.masked}”, but it really goes to the address below.</p>}
            <div className="card link-preview-url">{pending.url}</div>
            <label style={{ display: "flex", gap: 8, alignItems: "center", textTransform: "none", fontWeight: 500, fontSize: 14, color: "var(--text)" }}>
              <input type="checkbox" checked={trust} onChange={(e) => setTrust(e.target.checked)} style={{ width: "auto" }} />
              Trust {hostOf(pending.url)} from now on
            </label>
          </div>
          <div className="modal-foot">
            <button className="btn" onClick={() => setPending(null)}>Go back</button>
            <button className="btn primary" onClick={() => {
              if (trust) onTrust(hostOf(pending.url));
              go(pending.url);
              setPending(null);
            }}>Visit site</button>
          </div>
        </Modal>
      )}
    </LinkContext.Provider>
  );
}

export function useLinks() {
  return useContext(LinkContext);
}

export function Link({ href, children, masked }: { href: string; children: ReactNode; masked?: string }) {
  const { open } = useLinks();
  return (
    <a href={href} target="_blank" rel="noopener noreferrer nofollow" className="msg-link"
      onClick={(e) => { e.preventDefault(); open(href, masked); }}>{children}</a>
  );
}

// ---------------------------------------------------------------------------- formatting

export type MentionInfo = { people: Record<string, Person>; roles: Role[]; me?: string; onPerson?: (uuid: string) => void };

const URL_RE = /^https?:\/\/[^\s<>()]+[^\s<>().,;:!?'"\]]/;

function inline(text: string, m: MentionInfo, key = "k"): ReactNode[] {
  const out: ReactNode[] = [];
  let buf = "";
  let i = 0;
  let n = 0;
  const flush = () => { if (buf) { out.push(buf); buf = ""; } };
  const push = (node: ReactNode) => { flush(); out.push(<span key={`${key}-${n++}`}>{node}</span>); };
  const wrap = (open: string, close: string, render: (inner: string) => ReactNode) => {
    if (!text.startsWith(open, i)) return false;
    const end = text.indexOf(close, i + open.length);
    if (end <= i + open.length) return false;
    push(render(text.slice(i + open.length, end)));
    i = end + close.length;
    return true;
  };
  while (i < text.length) {
    const rest = text.slice(i);
    // `code`
    if (text[i] === "`") {
      const end = text.indexOf("`", i + 1);
      if (end > i + 1) { push(<code className="inline-code">{text.slice(i + 1, end)}</code>); i = end + 1; continue; }
    }
    // mentions
    let mm = rest.match(/^<@([0-9a-f]{32})>/);
    if (mm) {
      const p = m.people[mm[1]];
      push(<span className={`mention${mm[1] === m.me ? " me" : ""}`} onClick={() => m.onPerson?.(mm![1])}>@{p?.nickname || p?.name || "someone"}</span>);
      i += mm[0].length; continue;
    }
    mm = rest.match(/^<@&([0-9a-f-]{36})>/);
    if (mm) {
      const r = m.roles.find((x) => x.id === mm![1]);
      push(<span className="mention role" style={r?.color ? { color: r.color, background: `${r.color}22` } : undefined}>@{r?.name ?? "deleted role"}</span>);
      i += mm[0].length; continue;
    }
    mm = rest.match(/^@(everyone|here)\b/);
    if (mm && (i === 0 || /\s/.test(text[i - 1]))) { push(<span className="mention">@{mm[1]}</span>); i += mm[0].length; continue; }
    // <https://no-preview> and plain links
    mm = rest.match(/^<(https?:\/\/[^\s>]+)>/);
    if (mm) { push(<Link href={mm[1]}>{mm[1]}</Link>); i += mm[0].length; continue; }
    mm = rest.match(URL_RE);
    if (mm && (i === 0 || /[\s(*_~|>]/.test(text[i - 1]))) { push(<Link href={mm[0]}>{mm[0]}</Link>); i += mm[0].length; continue; }
    // [text](https://link)
    mm = rest.match(/^\[([^\]\n]{1,200})\]\((https?:\/\/[^\s)]+)\)/);
    if (mm) {
      const label = mm[1];
      push(<Link href={mm[2]} masked={label}>{inline(label, m, `${key}-${n}l`)}</Link>);
      i += mm[0].length; continue;
    }
    if (wrap("||", "||", (s) => <Spoiler>{inline(s, m, `${key}-${n}s`)}</Spoiler>)) continue;
    if (wrap("**", "**", (s) => <b>{inline(s, m, `${key}-${n}b`)}</b>)) continue;
    if (wrap("__", "__", (s) => <u>{inline(s, m, `${key}-${n}u`)}</u>)) continue;
    if (wrap("~~", "~~", (s) => <s>{inline(s, m, `${key}-${n}x`)}</s>)) continue;
    if ((text[i] === "*" || text[i] === "_") && text[i + 1] !== " " && wrap(text[i], text[i], (s) => <i>{inline(s, m, `${key}-${n}i`)}</i>)) continue;
    // \* escapes
    if (text[i] === "\\" && /[*_~`|<\\[]/.test(text[i + 1] ?? "")) { buf += text[i + 1]; i += 2; continue; }
    buf += text[i++];
  }
  flush();
  return out;
}

function Spoiler({ children }: { children: ReactNode }) {
  const [shown, setShown] = useState(false);
  return <span className={`spoiler${shown ? " shown" : ""}`} onClick={() => setShown(true)} title={shown ? undefined : "Spoiler - click to show"}>{children}</span>;
}

/** Lines: ```code blocks```, > quotes, # headings, - lists; the rest inline. */
export function Markdown({ text, mentions }: { text: string; mentions: MentionInfo }) {
  const blocks: ReactNode[] = [];
  const parts = text.split(/```/);
  parts.forEach((part, pi) => {
    if (pi % 2 === 1) {
      const nl = part.indexOf("\n");
      const lang = nl > 0 && /^[a-z0-9+#-]{1,16}$/i.test(part.slice(0, nl).trim()) ? part.slice(0, nl).trim() : "";
      const code = lang ? part.slice(nl + 1) : part.replace(/^\n/, "");
      blocks.push(<pre key={`c${pi}`} className="code-block" data-lang={lang || undefined}><code>{code.replace(/\n$/, "")}</code></pre>);
      return;
    }
    const lines = part.split("\n");
    lines.forEach((line, li) => {
      const k = `${pi}-${li}`;
      const last = li === lines.length - 1;
      let mm;
      if ((mm = line.match(/^(#{1,3}) (.+)/))) {
        const Tag = (["h3", "h4", "h5"] as const)[mm[1].length - 1];
        blocks.push(<Tag key={k} className="md-h">{inline(mm[2], mentions, k)}</Tag>);
      } else if ((mm = line.match(/^>>> ([\s\S]*)/))) {
        blocks.push(<blockquote key={k}>{inline(lines.slice(li).join("\n").slice(4), mentions, k)}</blockquote>);
        lines.length = li + 1;
      } else if ((mm = line.match(/^> ?(.*)/))) {
        blocks.push(<blockquote key={k}>{inline(mm[1], mentions, k)}</blockquote>);
      } else if ((mm = line.match(/^\s*[-*] (.+)/))) {
        blocks.push(<div key={k} className="md-li">• {inline(mm[1], mentions, k)}</div>);
      } else {
        blocks.push(<span key={k}>{inline(line, mentions, k)}{!last && "\n"}</span>);
      }
    });
  });
  return <>{blocks}</>;
}

/** Only emoji (up to 27)? Then they're shown bigger, like Discord. */
export function jumboEmoji(text: string) {
  const t = text.trim();
  if (!t || t.length > 60) return false;
  return /^(\p{Extended_Pictographic}|\p{Emoji_Component}|\s|‍|️)+$/u.test(t) && !/[0-9#*]/.test(t);
}
