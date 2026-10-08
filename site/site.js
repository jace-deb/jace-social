// Desktop app download buttons from the latest app release, and the server status.
const REPO = "jace-deb/jace-social";
const PLATFORMS = [
  { id: "windows", suffix: "-windows-x64.exe", label: "Windows", note: "10 / 11" },
  { id: "mac-arm", suffix: "-macos-arm64.app.zip", label: "macOS", note: "Apple Silicon" },
  { id: "mac-intel", suffix: "-macos-x86_64.app.zip", label: "macOS", note: "Intel" },
  { id: "linux", suffix: "-x86_64.AppImage", label: "Linux", note: "AppImage" },
];

function guessPlatform() {
  const ua = navigator.userAgent;
  if (/Windows/i.test(ua)) return "windows";
  if (/Mac OS X|Macintosh/i.test(ua)) return "mac-arm";
  if (/Linux|X11/i.test(ua) && !/Android/i.test(ua)) return "linux";
  return null;
}

async function fillDownloads() {
  const box = document.getElementById("downloads");
  const note = document.getElementById("release-note");
  try {
    const r = await fetch(`https://api.github.com/repos/${REPO}/releases?per_page=20`);
    if (!r.ok) throw new Error(r.status);
    const rel = (await r.json()).find((x) => x.tag_name.startsWith("app-v") && !x.draft);
    if (!rel) return;
    const mine = guessPlatform();
    for (const p of PLATFORMS) {
      const asset = rel.assets.find((a) => a.name.endsWith(p.suffix));
      if (!asset) continue;
      const a = document.createElement("a");
      a.className = "btn" + (p.id === mine ? " mine" : "");
      a.href = asset.browser_download_url;
      a.innerHTML = `⬇ ${p.label} <small>${p.note} · ${Math.round(asset.size / 1048576)} MB</small>`;
      box.append(a);
    }
    box.querySelector('a[href$="/releases"]')?.remove();
    note.innerHTML = `Desktop app ${rel.tag_name.replace(/^app-v/, "")} · <a href="${rel.html_url}">release notes</a> · free and open source`;
  } catch { /* keep the plain links */ }
}

async function socialStatus() {
  const el = document.getElementById("social-status");
  const dot = el.querySelector(".dot");
  const text = el.querySelector(".status-text");
  try {
    const r = await fetch("https://jace-social.vercel.app/api/v1/health", { cache: "no-store" });
    const d = await r.json();
    if (!r.ok || !d.ok) throw new Error();
    dot.className = "dot up";
    text.innerHTML = `<b>Jace Social is online</b><br><span style="color:var(--muted)">${d.players.toLocaleString()} player${d.players === 1 ? "" : "s"} signed up</span>`;
  } catch {
    dot.className = "dot down";
    text.innerHTML = `<b>Jace Social isn't responding right now</b><br><span style="color:var(--muted)">Try again in a moment.</span>`;
  }
}

fillDownloads();
socialStatus();
