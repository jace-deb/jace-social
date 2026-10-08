// POST {uuid}: add a friend to the group chat (any member)
// DELETE ?uuid=: leave (your own id) or, for the group's owner, remove someone
import { ApiError, areFriends, body, cleanUuid, db, handler, me } from "@/lib/server";
import { MAX_GROUP, channelFor, notifyChanged, postMessage, profilesById } from "@/lib/chat";

export const POST = handler(async (req, ctx) => {
  const p = await me(req);
  const { channel, members } = await channelFor((await ctx.params).id, p.uuid);
  if (channel.server_id) throw new ApiError(400, "That's a server channel");
  const uuid = cleanUuid((await body<{ uuid?: string }>(req)).uuid);
  if (members.includes(uuid)) throw new ApiError(409, "They're already in this group");
  if (members.length >= MAX_GROUP) throw new ApiError(400, `Group chats can have up to ${MAX_GROUP} people`);
  if (!(await areFriends(p.uuid, uuid))) throw new ApiError(403, "You can only add your friends");
  await db().from("channel_members").insert({ channel_id: channel.id, uuid });
  const who = (await profilesById([uuid]))[uuid];
  await postMessage(channel, [...members, uuid], null, `${p.display_name || p.name} added ${who?.name ?? "someone"}`, "system");
  await notifyChanged([...members, uuid], "groups", { kind: "changed", id: channel.id });
  return { ok: true };
});

export const DELETE = handler(async (req, ctx) => {
  const p = await me(req);
  const { channel, members } = await channelFor((await ctx.params).id, p.uuid);
  if (channel.server_id) throw new ApiError(400, "That's a server channel");
  const uuid = cleanUuid(new URL(req.url).searchParams.get("uuid") ?? p.uuid);
  if (uuid !== p.uuid && channel.owner !== p.uuid) throw new ApiError(403, "Only the group's owner can remove people");
  if (!members.includes(uuid)) throw new ApiError(404, "They aren't in this group");
  await db().from("channel_members").delete().eq("channel_id", channel.id).eq("uuid", uuid);
  const left = members.filter((m) => m !== uuid);
  if (!left.length) {                                  // last one out: delete the chat
    await db().from("channels").delete().eq("id", channel.id);
    return { ok: true };
  }
  if (channel.owner === uuid) await db().from("channels").update({ owner: left[0] }).eq("id", channel.id);
  const who = (await profilesById([uuid]))[uuid];
  await postMessage(channel, left, null, uuid === p.uuid ? `${who?.name ?? "Someone"} left` : `${p.display_name || p.name} removed ${who?.name ?? "someone"}`, "system");
  await notifyChanged([...left, uuid], "groups", { kind: "changed", id: channel.id });
  return { ok: true };
});
