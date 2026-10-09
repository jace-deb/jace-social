// Voice rooms: server voice channels, and calls in group chats.
// GET: who's in the room
// POST {action: "join" | "leave" | "state", muted?, deafened?, streaming?}
//   join: enter the room (leaves any other room) -> {participants, ice_servers}
//   state: mute/deafen/screen sharing, and the heartbeat (every ~20 s; gone after 45 s)
import { ApiError, body, db, handler, inboxesOf, me, notify } from "@/lib/server";
import { channelFor, profilesById, type Channel } from "@/lib/chat";
import { has, P } from "@/lib/perms";

const STALE_MS = 45_000;

async function participants(channelId: string) {
  const { data } = await db().from("voice_states").select("uuid, muted, deafened, streaming, joined_at, last_seen")
    .eq("channel_id", channelId).gt("last_seen", new Date(Date.now() - STALE_MS).toISOString()).order("joined_at");
  const people = await profilesById((data ?? []).map((v) => v.uuid));
  return (data ?? []).map((v) => ({ ...v, person: people[v.uuid] }));
}

async function tell(channel: Channel, members: string[]) {
  await notify(await inboxesOf(members), "voice", { channel_id: channel.id, server_id: channel.server_id });
}

export const GET = handler(async (req, ctx) => {
  const p = await me(req);
  const { channel } = await channelFor((await ctx.params).id, p.uuid);
  return { participants: await participants(channel.id) };
});

export const POST = handler(async (req, ctx) => {
  const p = await me(req);
  const { channel, members, perms } = await channelFor((await ctx.params).id, p.uuid);
  if (channel.server_id && channel.kind !== "voice") throw new ApiError(400, "That isn't a voice channel");
  const b = await body<{ action?: string; muted?: boolean; deafened?: boolean; streaming?: boolean }>(req);
  // clean up rooms people left without saying so
  await db().from("voice_states").delete().lt("last_seen", new Date(Date.now() - STALE_MS).toISOString());

  if (b.action === "join") {
    if (!has(perms, P.CONNECT)) throw new ApiError(403, "You can't join this voice channel");
    const now = await participants(channel.id);
    if (channel.user_limit && now.length >= channel.user_limit && !now.some((v) => v.uuid === p.uuid) && !has(perms, P.MANAGE_CHANNELS))
      throw new ApiError(403, "This voice channel is full");
    if (now.length >= 8 && !now.some((v) => v.uuid === p.uuid))
      throw new ApiError(403, "Voice rooms hold up to 8 people (everyone connects to everyone)");
    // one room at a time: leave the old one (and tell its people)
    const { data: old } = await db().from("voice_states").select("channel_id").eq("uuid", p.uuid).maybeSingle();
    await db().from("voice_states").delete().eq("uuid", p.uuid);
    if (old && old.channel_id !== channel.id) {
      const { data: oc } = await db().from("channels").select("*").eq("id", old.channel_id).maybeSingle();
      if (oc) {
        const { members: om } = await channelFor(oc.id, p.uuid).catch(() => ({ members: [] as string[] }));
        await tell(oc as Channel, om);
      }
    }
    await db().from("voice_states").insert({
      channel_id: channel.id, uuid: p.uuid, muted: Boolean(b.muted) || !has(perms, P.SPEAK), deafened: Boolean(b.deafened),
    });
    await tell(channel, members);
    return { participants: await participants(channel.id), can_speak: has(perms, P.SPEAK), can_stream: has(perms, P.STREAM) };
  }
  if (b.action === "leave") {
    await db().from("voice_states").delete().eq("uuid", p.uuid).eq("channel_id", channel.id);
    await db().from("voice_signals").delete().or(`sender.eq.${p.uuid},recipient.eq.${p.uuid}`).eq("channel_id", channel.id);
    await tell(channel, members);
    return { ok: true };
  }
  if (b.action === "state") {
    const { data: cur } = await db().from("voice_states").select("*").eq("uuid", p.uuid).eq("channel_id", channel.id).maybeSingle();
    if (!cur) throw new ApiError(404, "You aren't in this room");
    if (b.streaming && !has(perms, P.STREAM)) throw new ApiError(403, "You can't share your screen here");
    const patch = {
      last_seen: new Date().toISOString(),
      muted: b.muted ?? cur.muted, deafened: b.deafened ?? cur.deafened, streaming: b.streaming ?? cur.streaming,
    };
    if (!has(perms, P.SPEAK)) patch.muted = true;
    await db().from("voice_states").update(patch).eq("uuid", p.uuid).eq("channel_id", channel.id);
    if (b.muted !== undefined || b.deafened !== undefined || b.streaming !== undefined) await tell(channel, members);
    return { ok: true };
  }
  throw new ApiError(400, "action must be join, leave or state");
});
