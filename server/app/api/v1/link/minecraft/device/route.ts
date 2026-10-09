// POST: start linking Minecraft from the browser (same device code flow as sign-in)
import { handler, me } from "@/lib/server";
import { deviceStart } from "@/lib/minecraft";

export const POST = handler(async (req) => {
  await me(req);
  return deviceStart();
});
