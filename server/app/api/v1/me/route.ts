// GET: your own profile (everything friends see, plus account details)
// PATCH {display_name?, bio?, pronouns?, accent_color?, links?, status?, custom_status?, status_emoji?}
import { ApiError, body, db, friendsOf, handler, me, notify, publicProfile, supabaseUrl, type Profile } from "@/lib/server";

function own(p: Profile) {
  return {
    ...publicProfile({ ...p, status: p.status === "invisible" ? "online" : p.status }),
    status: p.status ?? "online",                   // your real choice, even when invisible
    display_name: p.display_name ?? null,
    minecraft_linked: p.mc_linked !== false, jace_linked: !!p.jace_sub, jace_name: p.jace_name ?? null,
    inbox: p.inbox, realtime: { url: supabaseUrl(), key: process.env.SUPABASE_PUBLISHABLE_KEY },
  };
}

export const GET = handler(async (req) => own(await me(req)));

const STATUSES = ["online", "idle", "dnd", "invisible"];
const text = (v: unknown, max: number, field: string) => {
  if (v === null || v === "") return null;
  if (typeof v !== "string") throw new ApiError(400, `${field} must be text`);
  const t = v.trim();
  if (t.length > max) throw new ApiError(400, `${field} can be up to ${max} characters`);
  return t || null;
};

export const PATCH = handler(async (req) => {
  const p = await me(req);
  const b = await body<Record<string, unknown>>(req);
  const patch: Partial<Profile> = {};
  if ("display_name" in b) patch.display_name = text(b.display_name, 32, "Display name");
  if ("bio" in b) patch.bio = text(b.bio, 300, "Bio");
  if ("pronouns" in b) patch.pronouns = text(b.pronouns, 24, "Pronouns");
  if ("custom_status" in b) patch.custom_status = text(b.custom_status, 80, "Custom status");
  if ("status_emoji" in b) patch.status_emoji = text(b.status_emoji, 16, "Status emoji");
  if ("accent_color" in b) {
    const c = text(b.accent_color, 7, "Color");
    if (c && !/^#[0-9a-fA-F]{6}$/.test(c)) throw new ApiError(400, "Color must look like #3ddc84");
    patch.accent_color = c;
  }
  if ("status" in b) {
    if (!STATUSES.includes(String(b.status))) throw new ApiError(400, "Status must be online, idle, dnd or invisible");
    patch.status = b.status as Profile["status"];
  }
  if ("links" in b) {
    if (!Array.isArray(b.links) || b.links.length > 5) throw new ApiError(400, "Up to 5 profile links");
    patch.links = b.links.map((l) => {
      const label = text((l as { label?: unknown })?.label, 32, "Link name") ?? "";
      const url = text((l as { url?: unknown })?.url, 300, "Link") ?? "";
      if (!/^https?:\/\/[^\s]+\.[^\s]+$/i.test(url)) throw new ApiError(400, `"${url}" isn't a web link (https://...)`);
      return { label: label || new URL(url).hostname.replace(/^www\./, ""), url };
    });
  }
  if (!Object.keys(patch).length) throw new ApiError(400, "Nothing to change");
  const { data, error } = await db().from("profiles").update(patch).eq("uuid", p.uuid).select("*").single();
  if (error) throw error;
  const friends = await friendsOf(p.uuid);
  await notify(friends.map((f) => f.inbox), "presence", { uuid: p.uuid });
  return own(data as Profile);
});
