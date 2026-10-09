// POST {ticket}: {pending: true} until the code is entered, then links that Minecraft account
import { ApiError, body, handler, me } from "@/lib/server";
import { devicePoll, linkMinecraft } from "@/lib/minecraft";

export const POST = handler(async (req) => {
  const p = await me(req);
  const { ticket } = await body<{ ticket?: string }>(req);
  if (!ticket) throw new ApiError(400, "ticket is required");
  const mc = await devicePoll(ticket);
  if (!mc) return { pending: true };
  return linkMinecraft(p, mc);
});
