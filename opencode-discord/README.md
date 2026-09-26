# Demon Dodo ↔ OpenCode bridge

One Demon Dodo process does two jobs:

1. **Chat:** your Discord DMs go to the OpenCode server on nitro (`127.0.0.1:4096`), the same server the phone app and MY AI use.
2. **Tool:** the agent controls Demon Dodo through the `demon_dodo` OpenCode tool, from any surface (PC, phone or Discord).

## Tool: `demon_dodo`

| Action | What |
|---|---|
| `servers` / `channels` / `members` | list servers, text channels, members |
| `send` | post to a channel, or DM a user; accepts files and replies |
| `broadcast` | post the same message to several channels |
| `read` | recent messages in a channel or DM |
| `edit` / `delete` / `react` | work on a message by its ID |
| `bulk_delete` | delete up to 100 recent messages (not older than 14 days) |
| `status` | set the bot's status and activity |

A channel can be an ID, `general`, `#general`, or `Server/#general`. @mentions don't ping anyone unless the agent sets `ping`.
The tool calls the bridge at `127.0.0.1:4099` (`control.js`), using the same password as the OpenCode server.

To read message text in servers, turn on **Message Content Intent** in the [Developer Portal](https://discord.com/developers/applications) under Bot. For the `members` action, also turn on **Server Members Intent** there and set `"membersIntent": true` in `config.json`.

## Chat

- Only DMs from `ownerId` get through. Messages from anyone else, and all server-channel messages, are ignored.
- Every message goes into the chat you used most recently, the same one `opencode attach -c` (MY AI) picks, so the phone, the PC and Discord share one conversation.
- Replies go back only to where you sent the message. A Discord message gets its reply in Discord. A phone message gets its reply in the phone app. The whole chat shows up on the phone and PC either way.
- If OpenCode asks for a permission or asks a question, the bot posts it in the DM. Answer with `yes` / `always` / `no`, or with the option number. You can also answer it on the phone.
- Any screenshot taken during a task is attached to the reply: the newest one by default, set with `screenshotsPerReply`.
- Files you attach in the DM are saved to `uploadsDir`, and the agent is told where they are.

Commands: `!new` (new chat) · `!stop` (stop the current task) · `!session` · `!help`

## Install on nitro

1. Copy this folder to `C:\Users\Allen\OpenCodeRemote\discord-bridge`.
2. Stop the old Demon Dodo program if it replies to DMs. It uses the same bot token, so both would answer.
3. In PowerShell, run:
   ```powershell
   powershell -ExecutionPolicy Bypass -File C:\Users\Allen\OpenCodeRemote\discord-bridge\install.ps1
   ```
   It asks for:
   - the bot token, from the [Discord Developer Portal](https://discord.com/developers/applications) under Demon Dodo → Bot → Reset Token
   - your Discord user ID: turn on Settings → Advanced → Developer Mode, then right-click your name → Copy User ID

   It then copies `demon_dodo.js` to `C:\Users\Allen\.config\opencode\tool\`, registers the logon task "OpenCode Discord" and starts it.
4. Restart the OpenCode server (or log out and back in) so it loads the tool.

The DM chat needs no privileged intents. Only the server-side tool actions above need them.

## Files

| File | What |
|---|---|
| `bridge.js` | the bridge: DM chat |
| `control.js` | the control API behind the `demon_dodo` tool |
| `tool/demon_dodo.js` | the OpenCode tool; goes in `.config\opencode\tool\` |
| `config.json` | token and owner ID. **Secret**, gitignored, created by `install.ps1` |
| `config.example.json` | template with nitro's paths |
| `start-discord.ps1` | restart loop that writes `bridge.log` |
| `install.ps1` | one-time setup and the logon task |
