// Permission bits, shared by the server (lib/perms.ts) and the app (role and channel settings).
export const P = {
  VIEW: 1, SEND: 2, ATTACH: 4, REACT: 8, MENTION_EVERYONE: 16, MANAGE_MESSAGES: 32,
  MANAGE_CHANNELS: 64, MANAGE_ROLES: 128, MANAGE_SERVER: 256, KICK: 512, BAN: 1024,
  INVITE: 2048, CHANGE_NICK: 4096, MANAGE_NICKS: 8192, CONNECT: 16384, SPEAK: 32768,
  STREAM: 65536, TIMEOUT: 131072, MANAGE_BOTS: 262144, ADMIN: 1073741824,
} as const;

export const ALL = Object.values(P).reduce((a, b) => a | b, 0);

/** What @everyone can do in a new server. */
export const DEFAULT_EVERYONE = P.VIEW | P.SEND | P.ATTACH | P.REACT | P.INVITE | P.CHANGE_NICK | P.CONNECT | P.SPEAK | P.STREAM;

export const has = (perms: number, p: number) => (perms & p) === p;

/** For settings screens: [bit, name, description], grouped. channel = also settable per channel. */
export const PERMISSION_INFO: { group: string; items: [number, string, string, boolean][] }[] = [
  { group: "General", items: [
    [P.VIEW, "See channels", "Read messages and see who's in voice", true],
    [P.MANAGE_CHANNELS, "Manage channels", "Create, edit and delete channels", true],
    [P.MANAGE_ROLES, "Manage roles", "Create roles and give them to people below their own", true],
    [P.MANAGE_SERVER, "Manage server", "Name, icon, links, onboarding and other settings", false],
    [P.MANAGE_BOTS, "Add bots", "Add bots to the server", false],
    [P.INVITE, "Create invites", "See and share the invite link", false],
  ] },
  { group: "Members", items: [
    [P.CHANGE_NICK, "Change nickname", "Set their own nickname here", false],
    [P.MANAGE_NICKS, "Manage nicknames", "Change other people's nicknames", false],
    [P.KICK, "Kick members", "Remove people (they can join again)", false],
    [P.BAN, "Ban members", "Remove people for good", false],
    [P.TIMEOUT, "Time out members", "Stop people talking for a while", false],
  ] },
  { group: "Text", items: [
    [P.SEND, "Send messages", "", true],
    [P.ATTACH, "Attach files", "Send pictures and files", true],
    [P.REACT, "Add reactions", "", true],
    [P.MENTION_EVERYONE, "Mention @everyone", "And roles that can't normally be mentioned", true],
    [P.MANAGE_MESSAGES, "Manage messages", "Delete anyone's messages, pin messages, skip slowmode", true],
  ] },
  { group: "Voice", items: [
    [P.CONNECT, "Connect", "Join voice channels", true],
    [P.SPEAK, "Speak", "", true],
    [P.STREAM, "Video", "Turn on their camera and share their screen", true],
  ] },
  { group: "Advanced", items: [
    [P.ADMIN, "Administrator", "Every permission, everywhere. Give this carefully.", false],
  ] },
];
