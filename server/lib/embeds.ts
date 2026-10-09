// Link previews ("embeds") for messages: the first few links' title, description, image
// and site name, read from the page's Open Graph / Twitter tags. Fetched by the server so
// people's IP addresses aren't sent to every site that's linked.
import { lookup } from "node:dns/promises";
import { isIP } from "node:net";

export type Embed = { url: string; title?: string; description?: string; image?: string; site?: string; color?: string; type: "link" | "image" | "video" };

const URL_RE = /https?:\/\/[^\s<>()]+[^\s<>().,;:!?'"\]]/gi;
const MAX_LINKS = 3;
const MAX_BYTES = 512 * 1024;

export function linksIn(text: string): string[] {
  const out: string[] = [];
  for (const m of text.matchAll(URL_RE)) {
    // <https://...> means "don't preview this one", like Discord
    if (text[m.index! - 1] === "<") continue;
    if (!out.includes(m[0])) out.push(m[0]);
    if (out.length >= MAX_LINKS) break;
  }
  return out;
}

/** No previews of this server's own network: refuse private, loopback and link-local addresses. */
function privateAddress(ip: string): boolean {
  if (isIP(ip) === 4) {
    const [a, b] = ip.split(".").map(Number);
    return a === 10 || a === 127 || a === 0 || (a === 169 && b === 254) || (a === 172 && b >= 16 && b <= 31)
      || (a === 192 && b === 168) || (a === 100 && b >= 64 && b <= 127) || a >= 224;
  }
  const v = ip.toLowerCase();
  return v === "::1" || v === "::" || v.startsWith("fc") || v.startsWith("fd") || v.startsWith("fe80")
    || (v.startsWith("::ffff:") && privateAddress(v.slice(7)));
}

async function safeUrl(raw: string): Promise<URL | null> {
  let u: URL;
  try { u = new URL(raw); } catch { return null; }
  if (!["http:", "https:"].includes(u.protocol) || u.username || u.password) return null;
  if (u.port && !["80", "443", ""].includes(u.port)) return null;
  const host = u.hostname.replace(/^\[|\]$/g, "");
  try {
    const addrs = isIP(host) ? [{ address: host }] : await lookup(host, { all: true });
    if (!addrs.length || addrs.some((a) => privateAddress(a.address))) return null;
  } catch {
    return null;
  }
  return u;
}

function decode(s: string) {
  return s.replace(/&amp;/g, "&").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, '"')
    .replace(/&#39;|&apos;/g, "'").replace(/&#(\d+);/g, (_, n) => String.fromCodePoint(Number(n))).trim();
}

function meta(html: string, ...names: string[]): string | undefined {
  for (const name of names) {
    const re = new RegExp(`<meta[^>]+(?:property|name)=["']${name}["'][^>]*>`, "i");
    const tag = html.match(re)?.[0];
    const content = tag?.match(/content=["']([^"']*)["']/i)?.[1];
    if (content) return decode(content);
  }
  return undefined;
}

const clip = (s: string | undefined, n: number) => (s && s.length > n ? s.slice(0, n - 1) + "…" : s);

async function one(raw: string): Promise<Embed | null> {
  let url = await safeUrl(raw);
  if (!url) return null;
  // follow up to 3 redirects by hand, checking each address
  for (let hop = 0; hop < 4; hop++) {
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), 3500);
    try {
      const r = await fetch(url, {
        redirect: "manual", signal: ctrl.signal,
        headers: { "User-Agent": "Mozilla/5.0 (compatible; JaceSocialBot/1.0; +https://jace-deb.github.io/jace-social/)", Accept: "text/html,image/*;q=0.8" },
      });
      if (r.status >= 300 && r.status < 400 && r.headers.get("location")) {
        url = await safeUrl(new URL(r.headers.get("location")!, url).toString());
        if (!url) return null;
        continue;
      }
      if (!r.ok) return null;
      const type = r.headers.get("content-type") ?? "";
      if (type.startsWith("image/")) return { url: raw, image: url.toString(), type: "image" };
      if (!type.includes("html")) return null;
      // read at most MAX_BYTES of the page
      const reader = r.body?.getReader();
      let html = "";
      let size = 0;
      const dec = new TextDecoder();
      while (reader && size < MAX_BYTES) {
        const { done, value } = await reader.read();
        if (done) break;
        size += value.length;
        html += dec.decode(value, { stream: true });
        if (/<\/head>/i.test(html)) break;
      }
      void reader?.cancel().catch(() => {});
      const title = meta(html, "og:title", "twitter:title") ?? decode(html.match(/<title[^>]*>([^<]*)<\/title>/i)?.[1] ?? "");
      const description = meta(html, "og:description", "twitter:description", "description");
      let image = meta(html, "og:image", "og:image:url", "twitter:image");
      if (image) { try { image = new URL(image, url).toString(); } catch { image = undefined; } }
      if (image && !image.startsWith("https://")) image = undefined;
      if (!title && !description && !image) return null;
      const video = /youtube\.com|youtu\.be|twitch\.tv|vimeo\.com/.test(url.hostname);
      return {
        url: raw, type: video ? "video" : "link",
        title: clip(title || undefined, 120), description: clip(description, 300), image,
        site: clip(meta(html, "og:site_name") ?? url.hostname.replace(/^www\./, ""), 60),
        color: meta(html, "theme-color")?.match(/^#[0-9a-f]{6}$/i)?.[0],
      };
    } catch {
      return null;
    } finally {
      clearTimeout(timer);
    }
  }
  return null;
}

/** Previews for the links in a message (at most 3, a few seconds at most). */
export async function embedsFor(text: string): Promise<Embed[]> {
  const links = linksIn(text);
  if (!links.length) return [];
  const all = await Promise.all(links.map((l) => one(l).catch(() => null)));
  return all.filter(Boolean) as Embed[];
}
