# Jace Social

**Website:** https://jace-deb.github.io/jace-social/ · **Web app:** https://jace-social.vercel.app/app

Friends, direct messages, group chats, servers and voice calls, with Minecraft built in. Use it:
- in your browser
- as a desktop app for Windows, macOS and Linux
- in [Jace Launcher](https://github.com/jace-deb/jace-launcher)
- inside Minecraft with the Jace Social mod

One account, the same friends everywhere.

## What's here

| Folder | What it is |
|---|---|
| `server/` | The Jace Social server and web app (Next.js on Vercel, Supabase database). See [server/README.md](server/README.md). |
| `app/` | The desktop app: the web app in its own window, plus Minecraft sign-in, notifications and a tray icon (Python + Qt). |
| `mod/` | The Jace Social mod for Minecraft 1.20.1-26.3 on Fabric/Quilt, NeoForge and Forge (Stonecutter). |
| `site/` | This project's website (GitHub Pages). |
| `packaging/` | Scripts that publish the mod and the desktop app to [Jace Store](https://jace-store-deb.vercel.app). |

## Accounts

Sign in with a **Jace** account (in the browser and the desktop app) or with **Minecraft** (in Jace Launcher, the desktop app and the mod). Link them so both work:
- **Jace Launcher:** Settings → Jace Social → **Link Jace**
- **Desktop app or web:** Settings → Linked accounts. **Link Minecraft** is only in the desktop app, because Microsoft sign-in can't run in a browser tab.

If both accounts already had friends or chats, linking merges them into one.

## Features

- **Friends:** add by Minecraft or Jace username, with requests, online status and unread counts.
- **Direct messages and group chats:** up to 10 people per group, with a name, picture, and adding or removing people.
- **Servers:**
  - text channels with topics
  - owner, admins and members
  - invite links
  - editing and deleting messages
  - a server icon and description
- **Status:** Online, Idle, Do Not Disturb or Invisible, plus a custom status with an emoji.
- **Rich presence:** the Minecraft version and loader, the server or world, the modpack, and how long you've been playing. It's reported by Jace Launcher and the mod.
- **Profiles:** picture, display name, pronouns, about me, profile color, and up to 5 links.
- **Voice calls:** one-to-one with friends, in the web app, the desktop app and Jace Launcher (all can call each other), and controlled from the mod.
- **Hosted worlds:** host from the mod with roles, plus LuckPerms and WorldEdit permission menus.

## Releases

- **Mod:** `git tag mod-vX.Y.Z && git push origin mod-vX.Y.Z` builds every Minecraft version and publishes it to Jace Store.
- **Desktop app:** `git tag app-vX.Y.Z && git push origin app-vX.Y.Z` builds every platform, makes a GitHub release and lists it on Jace Store.
- **Server and web app:** deployed by Vercel on every push to `main` (root directory `server`).

Both release workflows need the `JACE_STORE_TOKEN` repository secret.
