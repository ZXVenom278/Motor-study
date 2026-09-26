// OpenCode tool: act as the Demon Dodo Discord bot.
// Install: copy to C:\Users\Allen\.config\opencode\tool\demon_dodo.js (install.ps1 does this).
// Talks to the bridge's control API (discord-bridge\control.js) on 127.0.0.1:4099.

import { tool } from "@opencode-ai/plugin";
import fs from "node:fs";

const URL = process.env.DEMON_DODO_URL || "http://127.0.0.1:4099/discord";
const PASSWORD_FILE = process.env.DEMON_DODO_PASSWORD_FILE || "C:\\Users\\Allen\\OpenCodeRemote\\password.txt";

const z = tool.schema;

export default tool({
  description:
    "Act as the Demon Dodo Discord bot (Allen's bot). Actions: " +
    "servers (list servers); channels {guild}; send {channel|user, content, files?, reply_to?, ping?}; " +
    "broadcast {channels[], content, files?}; read {channel|user, limit?}; edit/delete {channel|user, message}; " +
    "bulk_delete {channel, count<=100}; react {channel|user, message, emoji}; members {guild, limit?}; " +
    "status {content?, activity?, presence?}. " +
    "channel = id, name, #name or 'Server/#name'. user = id or username (sends a DM). " +
    "Mentions don't ping unless ping=true. Only post, delete or DM when Allen asked for it.",
  args: {
    action: z.enum(["servers", "channels", "send", "broadcast", "read", "edit", "delete", "bulk_delete", "react", "members", "status"]),
    guild: z.string().optional().describe("Server id or name"),
    channel: z.string().optional().describe("Channel id, name, #name, or Server/#name"),
    channels: z.array(z.string()).optional().describe("broadcast: channels to post to"),
    user: z.string().optional().describe("User id or username; targets their DM"),
    content: z.string().optional().describe("Message text (max 2000 chars), or status text"),
    files: z.array(z.string()).optional().describe("Local file paths or URLs to attach"),
    message: z.string().optional().describe("Message id for edit/delete/react"),
    reply_to: z.string().optional().describe("send: message id to reply to"),
    emoji: z.string().optional(),
    count: z.number().optional().describe("bulk_delete: how many recent messages"),
    limit: z.number().optional(),
    ping: z.boolean().optional().describe("Allow @mentions to notify"),
    activity: z.enum(["Custom", "Playing", "Listening", "Watching", "Competing"]).optional(),
    presence: z.enum(["online", "idle", "dnd", "invisible"]).optional(),
  },
  async execute(args) {
    const pw = fs.readFileSync(PASSWORD_FILE, "utf8").trim();
    let res;
    try {
      res = await fetch(URL, {
        method: "POST",
        headers: {
          authorization: "Basic " + Buffer.from(`opencode:${pw}`).toString("base64"),
          "content-type": "application/json",
        },
        body: JSON.stringify(args),
      });
    } catch {
      return "Demon Dodo bridge is not running (task \"OpenCode Discord\"). Check discord-bridge\\bridge.log.";
    }
    const data = await res.json().catch(() => ({ ok: false, error: `HTTP ${res.status}` }));
    if (!data.ok) return `Error: ${data.error}`;
    return JSON.stringify(data.result, null, 1);
  },
});
