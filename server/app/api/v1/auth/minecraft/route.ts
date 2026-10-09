// POST: start Minecraft sign-in in the browser -> {user_code, verification_uri, interval, expires_at, ticket}
// (Microsoft's device code flow; see lib/minecraft.ts)
import { handler } from "@/lib/server";
import { deviceStart } from "@/lib/minecraft";

export const POST = handler(async () => deviceStart());
