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
- **Desktop app or web:** Settings → Linked accounts. In the browser, Minecraft sign-in and linking use a code you enter at microsoft.com/link.

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

## Desktop app

Each download is the app itself: `JaceSocial-<ver>-windows-x64.exe`, `-macos-arm64.app.zip` / `-macos-x86_64.app.zip`, or `-x86_64.AppImage`. The first time it runs, a setup wizard installs it, then starts the installed copy and deletes the download. Setup can't be skipped; cancelling closes the app.

| | Installs to | Options |
|---|---|---|
| Windows | `%LOCALAPPDATA%\Programs\Jace Social` (no admin) | Start menu, desktop shortcut, start with Windows, Installed apps entry, `jace-social` command |
| macOS | `/Applications` or `~/Applications` | Dock, desktop shortcut, open at login, `jace-social` command, remove the quarantine flag |
| Linux | `~/Applications` | menu entry, desktop shortcut, start at login, `jace-social` command, app center info |

Your sign-in and settings live outside the app folder (`%APPDATA%\Jace Social`, `~/Library/Application Support/Jace Social` or `~/.local/share/jace-social`), so updates and reinstalls keep them. The app isn't signed: on a Mac, right-click → **Open** the first time (macOS 15+: System Settings → Privacy & Security → **Open Anyway**); on Windows, **More info → Run anyway**.

Command line: `--install` (the wizard), `--install --yes` (defaults, no questions), `--uninstall`, `--uninstall --purge` (also your sign-in and settings), `--hidden` (start in the tray), `--help`.

Build it with `python app/packaging/build.py` on Windows or macOS, or `app/packaging/build_appimage.sh` on Linux. PyInstaller can't cross-compile, so releases are built by GitHub Actions.

## Releases

- **Mod:** `git tag mod-vX.Y.Z && git push origin mod-vX.Y.Z` builds every Minecraft version and publishes it to Jace Store.
- **Desktop app:** `git tag app-vX.Y.Z && git push origin app-vX.Y.Z` builds every platform, tests that each download installs, updates and uninstalls itself, makes a GitHub release and lists it on Jace Store. Bump `APP_VERSION` in `app/jace_social_app/__init__.py` and add a `## X.Y.Z` section to `app/CHANGELOG.md` first.
- **Server and web app:** deployed by Vercel on every push to `main` (root directory `server`).

Both release workflows need the `JACE_STORE_TOKEN` repository secret.
