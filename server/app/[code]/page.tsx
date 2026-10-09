// jace-social.vercel.app/<invite code or custom link>: a Discord-style invite page.
// Choose the desktop app (jacesocial:// link) or the web app. Rendered on the server so
// link previews (here, Discord, ...) show the server's name and icon.
import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { db } from "@/lib/server";
import InviteLanding from "@/components/InviteLanding";

export const dynamic = "force-dynamic";

async function load(code: string) {
  if (!/^[A-Za-z0-9_-]{3,32}$/.test(code)) return null;
  const cols = "id, name, icon_url, banner_url, description, accent_color, vanity, invites_paused";
  let { data } = await db().from("servers").select(cols).eq("invite_code", code).maybeSingle();
  if (!data) ({ data } = await db().from("servers").select(cols).eq("vanity", code.toLowerCase()).maybeSingle());
  if (!data) return null;
  const { data: ids } = await db().from("server_members").select("uuid").eq("server_id", data.id).limit(2000);
  const fresh = new Date(Date.now() - 3 * 60_000).toISOString();
  const { count: online } = ids?.length
    ? await db().from("profiles").select("uuid", { count: "exact", head: true }).in("uuid", ids.map((m) => m.uuid)).gt("last_seen", fresh)
    : { count: 0 };
  return { ...data, members: ids?.length ?? 0, online: online ?? 0 };
}

export async function generateMetadata({ params }: { params: Promise<{ code: string }> }): Promise<Metadata> {
  const s = await load((await params).code).catch(() => null);
  if (!s) return { title: "Invite - Jace Social" };
  const title = `Join ${s.name} on Jace Social`;
  const description = s.description ?? `${s.members} member${s.members === 1 ? "" : "s"} · Chat, voice and Minecraft with friends.`;
  return {
    title, description,
    openGraph: { title, description, siteName: "Jace Social", images: s.icon_url ? [{ url: s.icon_url }] : [] },
    twitter: { card: "summary", title, description },
    other: { "theme-color": s.accent_color ?? "#3ddc84" },
  };
}

export default async function InvitePage({ params }: { params: Promise<{ code: string }> }) {
  const code = (await params).code;
  if (code.includes(".")) notFound();            // favicon.ico, robots.txt and friends
  const s = await load(code).catch(() => null);
  return <InviteLanding code={code} server={s} />;
}
