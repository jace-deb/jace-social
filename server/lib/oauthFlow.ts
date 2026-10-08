// Shared by "Sign in with Jace" and "Link Jace": one oauth_requests row per browser round trip.
import { randomBytes } from "node:crypto";
import { db, sha256 } from "./server";
import { jaceAuthorizeUrl } from "./jace";

/**
 * Start a round trip. Web pages are redirected back to `returnTo` with the result;
 * apps get a poll key and ask /auth/jace/poll until the browser part is done.
 */
export async function startJace(purpose: "signin" | "link", opts: { linkUuid?: string; poll?: boolean; returnTo?: string }) {
  const state = randomBytes(24).toString("base64url");
  const pollKey = opts.poll ? randomBytes(24).toString("base64url") : null;
  await db().from("oauth_requests").delete().lt("created_at", new Date(Date.now() - 15 * 60_000).toISOString());
  const { error } = await db().from("oauth_requests").insert({
    state, purpose, link_uuid: opts.linkUuid ?? null, poll_hash: pollKey ? sha256(pollKey) : null,
    return_to: safeReturn(opts.returnTo),
  });
  if (error) throw error;
  return { url: jaceAuthorizeUrl(state), state, poll_key: pollKey };
}

/** Only our own pages (no open redirects). */
export function safeReturn(p: unknown): string {
  return typeof p === "string" && /^\/(?!\/)[\w\-/.?=&%#]*$/.test(p) ? p : "/app";
}
