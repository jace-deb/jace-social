// POST (image body): set the group's picture
import { ApiError, db, handler, me } from "@/lib/server";
import { channelFor, notifyChanged } from "@/lib/chat";
import { uploadImage } from "@/lib/images";

export const POST = handler(async (req, ctx) => {
  const p = await me(req);
  const { channel, members } = await channelFor((await ctx.params).id, p.uuid);
  if (channel.server_id) throw new ApiError(400, "That's a server channel");
  const url = await uploadImage(req, `groups/${channel.id}`);
  await db().from("channels").update({ icon_url: url }).eq("id", channel.id);
  await notifyChanged(members, "groups", { kind: "changed", id: channel.id });
  return { icon_url: url };
});
