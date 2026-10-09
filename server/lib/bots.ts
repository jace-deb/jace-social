// Bots (filled in below as they're built): block bots, the official Jace bot, and code bots.
import type { Channel } from "./chat";
import type { Profile } from "./server";

/** A message was posted in a group chat or server channel. */
export async function onChannelMessage(_channel: Channel, _members: string[], _sender: Profile, _message: Record<string, unknown>) {
  // see below
}

/** Someone joined a server. */
export async function onMemberJoin(_serverId: string, _who: Profile) {
  // see below
}
