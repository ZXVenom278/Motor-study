# Handoff: OpenCode PC agent + phone access

State as of 2026-09-26 (updated end of day). Everything runs on Allen's PC "nitro" (Windows 11, RTX 4050 6 GB). No secrets in this file; it points to the files that hold them.

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
| Discord bridge (Demon Dodo) | `C:\Users\Allen\OpenCodeRemote\discord-bridge\` (source: `opencode-discord/` in this repo) | `bridge.js` (DM chat) and `control.js` (control API on `127.0.0.1:4099`); logon task "OpenCode Discord" runs `start-discord.ps1`; log in `bridge.log` |
| Demon Dodo tool | `...\opencode\tool\demon_dodo.js` | the agent acts as the bot: send, DM, read, edit, delete, bulk delete, broadcast, react, status |
| Discord bot token + owner ID | `...\discord-bridge\config.json` | secret; created by `install.ps1` |
| Unused leftovers | `OpenCodeRemote\bridge.js`, `OpenCodeRemote\public\index.html` (incomplete), `OpenCodeRemote\test\` | Safe to delete; not used by anything |

## Status: Discord bridge (Demon Dodo)

**Code is done and pushed. It is NOT installed on nitro yet.**

- Code: `opencode-discord/` on branch `claude/opencode-pc-phone-handoff-soouje`, [PR #2](https://github.com/ZXVenom278/Motor-study/pull/2) (open)
- Tested in the cloud against a real OpenCode server with a simulated Discord: owner-only DMs, shared memory across PC/phone/Discord, permission prompts in the DM, `demon_dodo` actions. The download step of `get.ps1` was tested against GitHub.
- Not tested: the real Discord login, and the Windows-only parts of `install.ps1` (scheduled task, finding the old bot, restarting OpenCode). No Windows machine or real token was available.

### Next step: install on nitro

Run this in PowerShell on nitro, and answer the questions it asks:

```powershell
irm https://raw.githubusercontent.com/ZXVenom278/Motor-study/claude/opencode-pc-phone-handoff-soouje/opencode-discord/get.ps1 | iex
```

The installer does the following:
- Finds the old Demon Dodo's token, or asks for it.
- Asks for Allen's Discord user ID.
- Offers to switch off the old Demon Dodo (processes, tasks, startup items, old OpenCode tools).
- Installs the `demon_dodo` tool and the logon task "OpenCode Discord".
- Restarts the OpenCode server.
- Runs a health check.

A cloud session can't reach nitro. Run it yourself, or have a Claude session running on nitro run it (e.g. the Remote Control session "Command prompt troubleshooting").

### After install
- In the [Discord Developer Portal](https://discord.com/developers/applications), open Demon Dodo → Bot and turn on **Message Content Intent**. Without it, `read` returns messages with empty text. For `members`, also turn on **Server Members Intent** and set `"membersIntent": true` in `config.json`.
- DM Demon Dodo from Allen's account to test. The bot answers `!help`, `!new`, `!stop` and `!session`.
- Turn on 2FA for Allen's Discord account, because a DM to the bot can control the PC.
- Log: `C:\Users\Allen\OpenCodeRemote\discord-bridge\bridge.log`

### Open items
- **Before merging PR #2:** `get.ps1` and the one-liner download from the branch. Switch them to `main` before merging, or they break when the branch is deleted.
- **Unattended mode:** a mode where the OpenCode agent runs the installer with no prompts was proposed. The permission system blocked it, so it was never added. It needs Allen's explicit OK.
- **Old Demon Dodo:** its code and location on nitro are unknown. The installer searches for it by the name "dodo". Anything else must be switched off by hand, or two bots will reply to each DM.
- **Public repo:** this repo is public. `config.json` (token) is gitignored, so never commit it.
