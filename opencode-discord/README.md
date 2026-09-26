# Demon Dodo ↔ OpenCode bridge

Connects Demon Dodo to the OpenCode server on nitro (`127.0.0.1:4096`), the same server the phone app and MY AI use.

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

   It then registers the logon task "OpenCode Discord" and starts it.

No privileged intents are needed, because bots receive DM text without them.

## Files

| File | What |
|---|---|
| `bridge.js` | the bridge |
| `config.json` | token and owner ID. **Secret**, gitignored, created by `install.ps1` |
| `config.example.json` | template with nitro's paths |
| `start-discord.ps1` | restart loop that writes `bridge.log` |
| `install.ps1` | one-time setup and the logon task |
