# Jace Social server and web app

The server behind Jace Social: friends, messages, group chats, servers and hosted worlds. It serves the web app at `/app` too.
Jace Launcher, the desktop app and the Jace Social mod all talk to it. It's a Next.js app on Vercel with a Supabase database.

## How it works

- **Minecraft sign-in in the browser:** browsers can't talk to Xbox Live, so the web app uses Microsoft's device code flow. You enter a code at microsoft.com/link, and the server swaps the result for your Minecraft profile (Microsoft → Xbox Live → Minecraft). Unlike the other sign-ins, the server briefly holds those tokens. It uses them once to read the profile and never stores them (`lib/minecraft.ts`).
- **Sign-in (desktop app, Jace Launcher, mod):** works like joining a Minecraft server. The server hands out a one-time id, the client tells Mojang it "joined" it, and the server asks Mojang to confirm (`hasJoined`). Only the real account owner can do that. Minecraft tokens never reach this server, and only Microsoft accounts work.
- **Database access:** every table has row-level security on and no policies, so only the server (secret key) can read or write. Players go through `/api/v1/*` with a bearer token.
- **Live updates:** each player has a private, unguessable Supabase Realtime channel. Broadcasts only say what changed (never message text), and clients then fetch through the API.
- **Hosted worlds:** the mod opens your world to LAN, the e4all mod gives it a public address, and the address is shared with friends through your status.

## Setup (one time)

1. **Supabase:** create a free project at <https://supabase.com>.
   - **Security options when creating the project:** Data API **on**, "Automatically expose new tables" **off**, automatic RLS **on**.
   - **SQL Editor:** run `supabase/schema.sql`, then `supabase/002_calls.sql`, then `supabase/003_social.sql`. 003 adds Jace accounts, profiles, group chats, servers and the `avatars` storage bucket.
   - **Realtime → Settings:** make sure public channel access is allowed (it's the default).
   - **Project Settings → API Keys:** copy the project URL, the **publishable** key and the **secret** key.
2. **Vercel:** New Project, then import `jace-deb/jace-social`.
   - **Root Directory:** `server`
   - **Project name:** `jace-social` (that gives `https://jace-social.vercel.app`, the address the launcher and mod use).
   - **Environment variables:** `SUPABASE_URL`, `SUPABASE_SECRET_KEY`, `SUPABASE_PUBLISHABLE_KEY` (see `.env.example`).
   - **Sign in with Jace:** register an app at Jace with this redirect URI:
     `https://jace-social.vercel.app/api/v1/auth/jace/callback`
     Then add `JACE_OAUTH_CLIENT_ID` and `JACE_OAUTH_CLIENT_SECRET`.
   - Deploy.

## API (`/api/v1`, `Authorization: Bearer <token>`)

| Method | Path | |
|---|---|---|
| POST | `auth/start` → `{server_id}` | step 1 of sign-in (no auth) |
| POST | `auth/finish` `{name, server_id}` | step 2: returns `{token, uuid, inbox, realtime}` |
| POST | `auth/minecraft` → `{user_code, verification_uri, ticket}` | browser Minecraft sign-in (no auth) |
| POST | `auth/minecraft/poll` `{ticket}` | `{pending}` until the code is entered, then like `auth/finish` |
| POST | `link/minecraft/device`, `link/minecraft/device/poll` `{ticket}` | the same, to link Minecraft to a signed-in Jace account |
| POST | `auth/signout` | |
| GET | `me` | |
| GET | `friends` | friends with status and unread counts, plus incoming and outgoing requests |
| POST | `friends` `{name}` | send a request (works even if they haven't signed up yet) |
| DELETE | `friends?uuid=` | remove a friend or cancel/decline a request |
| POST | `friends/respond` `{uuid, accept}` | |
| POST | `presence` `{activity}` / `{offline: true}` | heartbeat every ~2 min |
| GET | `messages?with=&before=` | 50 newest, oldest first |
| POST | `messages` `{to, body}` | friends only, 500 chars max, 20/min |
| POST | `messages/read` `{with}` | |
| POST | `calls` `{to, call_id, kind, sdp?}` | voice call setup (offer / answer / hangup), friends only |
| GET | `calls?id=` | read a call signal sent to you (the live `call` event carries its id) |
| GET | `calls/ice` | STUN/TURN servers for calls |

## Voice calls

Calls work in the web app, the desktop app and Jace Launcher, and any of them can call any other (the Jace Social mod controls calls through the launcher). The web app uses the browser's WebRTC (`lib/calls.ts`), the launcher uses aiortc; both send a full description once ICE gathering finishes, with no trickle. Audio goes straight between the two players. When a direct connection isn't possible, it goes through Cloudflare's free TURN relay.

Setup:
1. In Supabase's **SQL Editor**, run `supabase/002_calls.sql`.
2. Optional, for the relay: in the Cloudflare dashboard, go to **Realtime → TURN Server → Create**. Then add these Vercel environment variables and redeploy:
   - `CLOUDFLARE_TURN_KEY_ID`: the Turn Token ID
   - `CLOUDFLARE_TURN_API_TOKEN`: the API token
   Without them, calls still work for most home networks (STUN only).
