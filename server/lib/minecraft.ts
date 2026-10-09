// Minecraft sign-in for the browser, with Microsoft's device code flow: the page shows
// a short code, the player enters it at microsoft.com/link, and this server swaps the
// result for the Minecraft profile (Microsoft -> Xbox Live -> Minecraft). Browsers can't
// do that exchange themselves (Xbox Live doesn't allow cross-site requests).
//
// Unlike the desktop app and Jace Launcher (which only prove the account with Mojang's
// "joined server" check), this means the server briefly holds the player's Microsoft
// and Minecraft tokens. It uses them once to read the profile and never stores them.
import { createCipheriv, createDecipheriv, createHash, randomBytes } from "node:crypto";
import { ApiError, db, ensureProfile, type Profile } from "./server";

// Microsoft's own public client (Minecraft for Nintendo Switch), which allows the
// device code flow for Xbox Live; no Azure app registration needed.
const CLIENT_ID = "00000000441cc96b";
const SCOPE = "service::user.auth.xboxlive.com::MBI_SSL";
const MC = "https://api.minecraftservices.com";

/** The device code goes to the browser sealed (AES-GCM), so nothing needs storing here. */
function key() {
  const secret = process.env.SUPABASE_SECRET_KEY;
  if (!secret) throw new ApiError(500, "Server isn't configured");
  return createHash("sha256").update("minecraft-device:" + secret).digest();
}

function seal(data: object): string {
  const iv = randomBytes(12);
  const c = createCipheriv("aes-256-gcm", key(), iv);
  const enc = Buffer.concat([c.update(JSON.stringify(data)), c.final()]);
  return Buffer.concat([iv, c.getAuthTag(), enc]).toString("base64url");
}

function unseal<T>(ticket: string): T {
  try {
    const b = Buffer.from(ticket, "base64url");
    const d = createDecipheriv("aes-256-gcm", key(), b.subarray(0, 12));
    d.setAuthTag(b.subarray(12, 28));
    return JSON.parse(Buffer.concat([d.update(b.subarray(28)), d.final()]).toString());
  } catch {
    throw new ApiError(400, "Sign-in expired - start again");
  }
}

async function form(url: string, data: Record<string, string>) {
  const r = await fetch(url, { method: "POST", body: new URLSearchParams(data),
    headers: { "Content-Type": "application/x-www-form-urlencoded" } });
  return { status: r.status, json: await r.json().catch(() => ({})) as Record<string, any> };
}

async function post(url: string, body: object, headers: Record<string, string> = {}) {
  const r = await fetch(url, { method: "POST", body: JSON.stringify(body),
    headers: { "Content-Type": "application/json", Accept: "application/json", ...headers } });
  return { status: r.status, json: await r.json().catch(() => ({})) as Record<string, any> };
}

/** Step 1: a code for the player to enter at microsoft.com/link. */
export async function deviceStart() {
  const r = await form("https://login.live.com/oauth20_connect.srf",
    { client_id: CLIENT_ID, scope: SCOPE, response_type: "device_code" });
  if (r.status !== 200 || !r.json.device_code) throw new ApiError(502, "Microsoft sign-in isn't available right now - try again");
  const expires = Date.now() + Number(r.json.expires_in ?? 900) * 1000;
  return {
    user_code: String(r.json.user_code),
    verification_uri: String(r.json.verification_uri ?? "https://www.microsoft.com/link"),
    interval: Number(r.json.interval ?? 5),
    expires_at: new Date(expires).toISOString(),
    ticket: seal({ d: r.json.device_code, e: expires }),
  };
}

/** Step 2 (polled): null while the player hasn't finished, else their Minecraft profile. */
export async function devicePoll(ticket: string): Promise<{ id: string; name: string } | null> {
  const t = unseal<{ d: string; e: number }>(ticket);
  if (Date.now() > t.e) throw new ApiError(400, "The code expired - start again");
  const r = await form("https://login.live.com/oauth20_token.srf",
    { client_id: CLIENT_ID, grant_type: "urn:ietf:params:oauth:grant-type:device_code", device_code: t.d });
  if (r.json.error === "authorization_pending" || r.json.error === "slow_down") return null;
  if (r.json.error === "authorization_declined") throw new ApiError(400, "Sign-in was cancelled");
  if (r.json.error === "expired_token") throw new ApiError(400, "The code expired - start again");
  if (r.status !== 200 || !r.json.access_token) throw new ApiError(502, "Microsoft sign-in failed - try again");
  return minecraftProfile(String(r.json.access_token));
}

/** Microsoft token -> Xbox Live -> Minecraft -> {id, name}. The tokens aren't kept. */
async function minecraftProfile(ms: string): Promise<{ id: string; name: string }> {
  const xbl = await post("https://user.auth.xboxlive.com/user/authenticate", {
    Properties: { AuthMethod: "RPS", SiteName: "user.auth.xboxlive.com", RpsTicket: ms },
    RelyingParty: "http://auth.xboxlive.com", TokenType: "JWT" });
  if (xbl.status !== 200) throw new ApiError(502, `Xbox Live sign-in failed (${xbl.status})`);
  const uhs = xbl.json.DisplayClaims?.xui?.[0]?.uhs;
  const xsts = await post("https://xsts.auth.xboxlive.com/xsts/authorize", {
    Properties: { SandboxId: "RETAIL", UserTokens: [xbl.json.Token] },
    RelyingParty: "rp://api.minecraftservices.com/", TokenType: "JWT" });
  if (xsts.status !== 200) {
    const why: Record<number, string> = {
      2148916233: "This Microsoft account has no Xbox profile. Sign in at xbox.com first.",
      2148916235: "Xbox Live isn't available in your country.",
      2148916238: "This is a child account - an adult has to add it to a Microsoft family.",
    };
    throw new ApiError(400, why[xsts.json.XErr] ?? `Xbox sign-in failed (${xsts.json.XErr ?? xsts.status})`);
  }
  const mc = await post(`${MC}/authentication/login_with_xbox`, { identityToken: `XBL3.0 x=${uhs};${xsts.json.Token}` });
  if (mc.status !== 200) throw new ApiError(502, `Minecraft sign-in failed (${mc.status})`);
  const p = await fetch(`${MC}/minecraft/profile`, { headers: { Authorization: `Bearer ${mc.json.access_token}` } });
  if (p.status === 404) throw new ApiError(400, "This Microsoft account doesn't own Minecraft: Java Edition.");
  if (!p.ok) throw new ApiError(502, `Couldn't read the Minecraft profile (${p.status})`);
  const d = await p.json() as { id: string; name: string };
  return { id: d.id, name: d.name };
}

/**
 * Link a proven Minecraft account to a signed-in (Jace) player. If that Minecraft player
 * already uses Jace Social, the two accounts become one, keeping friends and chats from both.
 */
export async function linkMinecraft(p: Profile, mc: { id: string; name: string }) {
  if (p.uuid === mc.id) return { ok: true, uuid: mc.id };
  if (p.mc_linked !== false) throw new ApiError(409, "You already have a Minecraft account linked");
  const mcProfile = await ensureProfile(mc.id, mc.name, true);
  if (mcProfile.jace_sub && mcProfile.jace_sub !== p.jace_sub)
    throw new ApiError(409, "That Minecraft account is already linked to another Jace account");
  // the Jace-only account moves into the Minecraft one (its sessions too, so this token keeps working)
  const { error } = await db().rpc("merge_profiles", { src: p.uuid, dst: mc.id });
  if (error) throw error;
  return { ok: true, uuid: mc.id };
}
