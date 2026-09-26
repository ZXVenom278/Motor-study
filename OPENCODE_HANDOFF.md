# Handoff: OpenCode PC agent + phone access

State as of 2026-09-26. Everything runs on Allen's PC "nitro" (Windows 11, RTX 4050 6 GB). No secrets in this file; it points to the files that hold them.

## What it is

OpenCode is an AI agent that controls the PC: it runs commands, uses the mouse and keyboard, and takes screenshots. It runs as one background server. Allen reaches the same server, and the same chats, from:

- **The PC:** the desktop shortcut "MY AI" (`opencode attach`).
- **His phone:** the Android app OpenCode Remote, which loads OpenCode's web UI over Tailscale.
- **Discord:** DMs to the Demon Dodo bot (from Allen's user ID only), relayed by the Discord bridge. Replies go back to the DM.

All three use the same server and the same chat: whatever was used last, the rule `opencode attach -c` follows. Each surface gets replies only for messages sent from it.

## Pieces and where they live

| Piece | Location | Notes |
|---|---|---|
| OpenCode config | `C:\Users\Allen\.config\opencode\opencode.json` | model, providers, permissions, the windows add-on (an MCP server) |
| Agent instructions | `C:\Users\Allen\.config\opencode\AGENTS.md` | honesty rules, PowerShell syntax, focus-before-typing, closing rules, the live view link |
| Status plugin | `...\opencode\plugin\status.js` | live "▶ step" lines + screenshots, safety blocks, "[Check]" window line, PC toasts |
| Custom tools | `...\opencode\tool\screenshot.js`, `focus_window.js`, `type_text.js` | shared code in `...\opencode\lib\capture.js` |
| Server launcher | `C:\Users\Allen\OpenCodeRemote\start-server.ps1` | runs `opencode serve` on `127.0.0.1:4096` in a restart loop, and starts `shots-server.js` |
| Logon task | Task Scheduler "OpenCode Remote" | runs `start-server.ps1` hidden at logon |
| Screenshot/live server | `C:\Users\Allen\OpenCodeRemote\shots-server.js` | `127.0.0.1:4098`; image files, full-screen viewer, live view |
| Server password | `C:\Users\Allen\OpenCodeRemote\password.txt` | HTTP Basic auth, user `opencode`; used by the server, the shots server, MY AI and the phone app |
| Screenshots | `C:\Users\Allen\Pictures\OpenCode Screenshots\` | |
| Phone uploads folder | `C:\Users\Allen\PhoneUploads\` | |
| PC shortcut | `C:\Users\Allen\OneDrive\Desktop\MY AI.lnk` | `opencode attach http://127.0.0.1:4096 --dir C:\Users\Allen -c`; old target saved in `OpenCodeRemote\MY-AI-shortcut-OLD.txt` |
| Android app source | `C:\Users\Allen\OpenCodeRemoteApp` | Kotlin WebView app; `README.md` has rebuild steps |
| App signing key | `OpenCodeRemoteApp\app\opencoderemote.keystore` (+ `keystore-BACKUP\`); passwords in `OpenCodeRemoteApp\gradle.properties` | **Back up off this PC.** Losing it means updates won't install over the app |
| APKs | `C:\Users\Allen\OpenCodeRemote\OpenCodeRemote-1.0.0/1.0.1/1.0.2.apk` | Latest is 1.0.2 (versionCode 3). Cert SHA-256 `f3c6ad07…cd48aa7` |
| Discord bridge | `C:\Users\Allen\OpenCodeRemote\discord-bridge\` (source: `opencode-discord/` in this repo) | `bridge.js`; logon task "OpenCode Discord" runs `start-discord.ps1`; log in `bridge.log` |
| Discord bot token + owner ID | `...\discord-bridge\config.json` | secret; created by `install.ps1` |
| Unused leftovers | `OpenCodeRemote\bridge.js`, `OpenCodeRemote\public\index.html` (incomplete), `OpenCodeRemote\test\` | Safe to delete; not used by anything |
