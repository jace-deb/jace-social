// GET: which build of the website is live (open apps compare it with their own, to offer a reload).
export const dynamic = "force-dynamic";

export function GET() {
  return Response.json({ build: process.env.VERCEL_GIT_COMMIT_SHA ?? "dev" }, { headers: { "Cache-Control": "no-store" } });
}
