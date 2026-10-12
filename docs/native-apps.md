# Native phone apps

Jace Social for phones is being rewritten as two native apps (the user's choice, 2026-10-12):

| | iPhone | Android |
|---|---|---|
| Folder | `ios/` | `android/` |
| Language / UI | Swift, SwiftUI (iOS 16+) | Kotlin, Jetpack Compose, Material 3 (Android 8+, API 26) |
| HTTP | URLSession (async/await) | OkHttp + kotlinx.serialization |
| Live events | URLSessionWebSocketTask (Phoenix protocol) | OkHttp WebSocket (Phoenix protocol) |
| Calls (WebRTC) | WebRTC (stasel/WebRTC, Swift Package) | stream-webrtc-android |
| Sign-in token | Keychain | EncryptedSharedPreferences |
| Jace sign-in | ASWebAuthenticationSession | Custom Tabs |
| Project file | XcodeGen (`ios/project.yml`) | Gradle (Kotlin DSL) |

Both talk to the same API as the website ([api.md](api.md)) and get the same live events. They keep
the earlier app's id (`dev.jacedeb.jacesocial`) and Android signing key, so 2.0.0 installs over the
Capacitor app (1.x, the website in a shell). Releasing works like before: bump `mobile/package.json`
and `mobile/CHANGELOG.md`, push; the "phone" part (native.yml) builds, tests and publishes them.

Building: only on GitHub (the "native" part of Build and release). The iPhone app needs a Mac, so it
is only ever built on GitHub's macOS runners. CI starts each app (Android emulator, iOS simulator),
takes screenshots and uploads them, which is how screens get checked.

The one exception to "native": the block bot editor is Blockly (JavaScript, no native version),
so it opens as a web page inside the app.

## The same structure on both

- `Api`: one function per endpoint, typed models that mirror `server/lib/client.ts`
- `Session`: token storage, sign-in (Jace in a browser plus polling; Minecraft device code), sign out,
  the account switcher
- `Live`: Supabase Realtime over a websocket. Join `realtime:<inbox>`, turn broadcasts into events:
  call, channel, command, dm_update, friends, groups, message, presence, servers, typing, voice,
  voice_signal
- `Store`: app state (me, friends, groups, servers, open chat), refreshed by API calls and live events
- Screens: listed below

## Features (✓ = done on both; released in 2.0.0)

- [x] Sign in with Jace / with Minecraft, sign out, account switcher
- [x] Friends: list with presence and activity, requests (send, accept, decline, cancel), remove
- [x] Direct messages: history, send, edit, delete, reply, reactions, typing, read state, pins
- [x] Messages: markdown, @mentions, link previews, attachments (upload, images, files), link warning
- [x] Group chats: create, rename, icon, members, leave
- [x] Servers: list, create, join by invite, channels by category, unread, text channels
- [x] Server settings: roles, permissions, channels, overrides, invites, vanity link, bans, onboarding,
      members (kick, ban, time out, nickname, roles), bots
- [x] Slash commands from bots
- [x] Profiles: view, edit (name, bio, pronouns, links, avatar, banner, accent), status and custom status
- [x] Voice calls with friends (WebRTC): ring, answer, mute, camera, screen sharing (Android; iPhone sees others'), move between devices
- [x] Voice channels and group calls (mesh), camera and screen sharing in them
- [x] Notifications while the app runs (in the background too); push when it's closed is still to do (APNs / FCM keys)
- [x] Bots: my bots, create, token, code editor + Try it, block editor (web view), activity log
- [x] Settings: appearance (themes, accent, text size, compact), notifications, privacy, links, accounts
- [x] Onboarding / welcome tour, server onboarding
- [x] Invite links (jacesocial://invite/<code>, https links)
- [x] Updates: the "new version" prompt (Android: APK; iPhone: release page)
