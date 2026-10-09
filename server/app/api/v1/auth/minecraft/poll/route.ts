// POST {ticket}: {pending: true} until the player has entered the code at microsoft.com/link,
// then signs them in like /auth/finish -> {token, uuid, inbox, realtime}
import { ApiError, body, createSession, ensureProfile, handler, supabaseUrl } from "@/lib/server";
import { devicePoll } from "@/lib/minecraft";

export const POST = handler(async (req) => {
  const { ticket } = await body<{ ticket?: string }>(req);
  if (!ticket) throw new ApiError(400, "ticket is required");
  const mc = await devicePoll(ticket);
  if (!mc) return { pending: true };
  const profile = await ensureProfile(mc.id, mc.name, true);
  const session = await createSession(profile.uuid);
  return {
    ...session,
    uuid: profile.uuid, name: profile.name, inbox: profile.inbox,
    realtime: { url: supabaseUrl(), key: process.env.SUPABASE_PUBLISHABLE_KEY },
  };
});
