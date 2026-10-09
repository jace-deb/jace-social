"use client";
// A small emoji picker for reactions and the message box (recently used first).
import { useEffect, useRef, useState } from "react";

const GROUPS: [string, string][] = [
  ["Smileys", "😀 😃 😄 😁 😆 😅 🤣 😂 🙂 🙃 😉 😊 😇 🥰 😍 🤩 😘 😋 😛 😜 🤪 😝 🤑 🤗 🤭 🤫 🤔 🤐 🤨 😐 😑 😶 😏 😒 🙄 😬 😌 😔 😪 🤤 😴 😷 🤒 🤕 🤢 🤮 🥵 🥶 🥴 😵 🤯 🤠 🥳 😎 🤓 🧐 😕 😟 🙁 😮 😯 😲 😳 🥺 😦 😧 😨 😰 😥 😢 😭 😱 😖 😣 😞 😓 😩 😫 🥱 😤 😡 😠 🤬 😈 👿 💀 ☠️ 💩 🤡 👻 👽 🤖"],
  ["People", "👋 🤚 ✋ 🖖 👌 🤌 🤏 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 👇 ☝️ 👍 👎 ✊ 👊 🤛 🤜 👏 🙌 👐 🤲 🤝 🙏 ✍️ 💪 🦾 🧠 👀 👁️ 👅 👄 🫶 🙋 🤷 🤦 🙇 💁 🙆 🙅"],
  ["Hearts", "❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 💯 💢 💥 💫 💦 💨 🕳️ 💬 💭 💤"],
  ["Nature", "🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🐔 🐧 🐦 🐤 🦆 🦅 🦉 🐺 🐗 🐴 🦄 🐝 🐛 🦋 🐌 🐞 🐢 🐍 🦎 🐙 🦑 🦀 🐠 🐟 🐬 🐳 🦈 🐊 🌵 🌲 🌳 🌴 🌱 🌿 🍀 🍁 🍂 🍃 🌸 🌺 🌻 🌹 🌷 🌍 🌙 ⭐ 🌟 ✨ ⚡ 🔥 🌈 ☀️ ⛅ ☁️ 🌧️ ⛈️ ❄️ ☃️ 💧 🌊"],
  ["Food", "🍏 🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🫐 🍒 🍑 🥭 🍍 🥥 🥝 🍅 🥑 🥦 🥕 🌽 🌶️ 🥔 🍞 🥐 🧀 🥚 🍳 🥓 🍔 🍟 🍕 🌭 🥪 🌮 🌯 🍝 🍜 🍣 🍱 🍩 🍪 🎂 🍰 🧁 🍫 🍬 🍭 🍿 ☕ 🍵 🧃 🥤 🍺 🍻 🥂 🍷"],
  ["Activities", "⚽ 🏀 🏈 ⚾ 🎾 🏐 🎱 🏓 🏸 🥅 ⛳ 🏹 🎣 🥊 🛹 ⛸️ 🎿 🏆 🥇 🥈 🥉 🏅 🎮 🕹️ 🎲 ♟️ 🎯 🎳 🧩 🎨 🎬 🎤 🎧 🎼 🎹 🥁 🎸 🎺 🎻 🎉 🎊 🎈 🎁 🎀"],
  ["Things", "⛏️ ⚔️ 🗡️ 🛡️ 🏹 🪓 🔨 🧱 🪵 💎 🧭 🗺️ 🏰 🏠 🏡 🏕️ ⛺ 🚀 🛸 ✈️ 🚗 🚲 ⛵ ⌚ 📱 💻 ⌨️ 🖥️ 🖱️ 💾 📷 🎥 📺 📻 🔋 🔌 💡 🔦 🕯️ 📚 📖 📝 ✏️ 📌 📎 🔑 🔒 🔓 🔔 🔕 📣 💰 💵 🎟️ 🧪 🧲 ⚙️ 🔧 🪛 🧰"],
  ["Symbols", "✅ ☑️ ✔️ ❌ ❎ ➕ ➖ ➗ ✖️ ❓ ❔ ❕ ❗ ‼️ ⁉️ ⚠️ 🚫 ⛔ 🔞 ♻️ 🔴 🟠 🟡 🟢 🔵 🟣 ⚫ ⚪ 🟥 🟧 🟨 🟩 🟦 🟪 ⬛ ⬜ ▶️ ⏸️ ⏹️ ⏺️ ⏭️ ⏮️ 🔁 🔀 🆗 🆕 🆒 🆓 🆙 🔝 🔜 1️⃣ 2️⃣ 3️⃣ 4️⃣ 5️⃣ 6️⃣ 7️⃣ 8️⃣ 9️⃣ 🔟 👑 🎯 📊"],
];
const RECENT = "jace_social_recent_emoji";

function recent(): string[] {
  try { return JSON.parse(localStorage.getItem(RECENT) ?? "[]"); } catch { return []; }
}

export function EmojiPicker({ onPick, onClose, style }: { onPick: (e: string) => void; onClose: () => void; style?: React.CSSProperties }) {
  const [tab, setTab] = useState(0);
  const ref = useRef<HTMLDivElement>(null);
  const recents = recent();
  useEffect(() => {
    const down = (e: MouseEvent) => { if (ref.current && !ref.current.contains(e.target as Node)) onClose(); };
    const key = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    setTimeout(() => document.addEventListener("mousedown", down), 0);
    document.addEventListener("keydown", key);
    return () => { document.removeEventListener("mousedown", down); document.removeEventListener("keydown", key); };
  }, [onClose]);
  const pick = (e: string) => {
    try { localStorage.setItem(RECENT, JSON.stringify([e, ...recent().filter((x) => x !== e)].slice(0, 24))); } catch { /* fine */ }
    onPick(e);
  };
  const list = tab < 0 ? recents : GROUPS[tab][1].split(" ");
  return (
    <div className="emoji-picker" ref={ref} style={style} role="dialog" aria-label="Pick an emoji">
      <div className="emoji-tabs">
        {recents.length > 0 && <button className={tab === -1 ? "active" : ""} onClick={() => setTab(-1)} title="Recent">🕘</button>}
        {GROUPS.map(([name, list], i) => (
          <button key={name} className={tab === i ? "active" : ""} onClick={() => setTab(i)} title={name}>{list.split(" ")[0]}</button>
        ))}
      </div>
      <div className="emoji-grid">
        {list.map((e) => <button key={e} onClick={() => pick(e)} title={e}>{e}</button>)}
      </div>
    </div>
  );
}

/** Reactions shown on every message's quick bar. */
export const QUICK_REACTIONS = ["👍", "❤️", "😂", "😮", "😢", "🔥"];
