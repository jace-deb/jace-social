// Sign in with Jace.
// GET ?return_to=/app  -> redirects the browser to Jace (web app)
// POST {}             -> {url, state, poll_key}: apps open url in a browser, then poll /auth/jace/poll
import { handler } from "@/lib/server";
import { startJace } from "@/lib/oauthFlow";

export const GET = handler(async (req) => {
  const { url } = await startJace("signin", { returnTo: new URL(req.url).searchParams.get("return_to") ?? undefined });
  return Response.redirect(url, 302);
});

export const POST = handler(async () => startJace("signin", { poll: true }));
