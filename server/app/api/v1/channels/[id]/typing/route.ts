// POST: "I'm typing" in a group chat or channel (shown to the others for ~8 seconds)
import { handler, inboxesOf, me, notify } from "@/lib/server";
import { channelFor } from "@/lib/chat";

export const POST = handler(async (req, ctx) => {
  const p = await me(req);
  const { channel, members } = await channelFor((await ctx.params).id, p.uuid);
  await notify(await inboxesOf(members.filter((m) => m !== p.uuid)), "typing",
    { channel_id: channel.id, server_id: channel.server_id, from: p.uuid, name: p.display_name || p.name });
  return { ok: true };
});
