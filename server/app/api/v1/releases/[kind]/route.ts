// GET /api/v1/releases/desktop | mobile: the newest app version, what's new and its downloads
// (the phone app checks this for updates). Public; cached.
import { latestApp, PHONE, PLATFORMS } from "@/lib/releases";

export async function GET(_req: Request, ctx: { params: Promise<{ kind: string }> }) {
  const { kind } = await ctx.params;
  if (kind !== "desktop" && kind !== "mobile") return Response.json({ error: "kind must be desktop or mobile" }, { status: 404 });
  const rel = kind === "mobile" ? await latestApp("mobile-v", PHONE) : await latestApp("app-v", PLATFORMS);
  if (!rel) return Response.json({ error: "Couldn't check for updates - try again later" }, { status: 503 });
  return Response.json(rel, { headers: { "Cache-Control": "public, s-maxage=600, stale-while-revalidate=3600" } });
}
