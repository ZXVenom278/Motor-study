// Local control API so the OpenCode agent can act as Demon Dodo (tool/demon_dodo.js calls this).
// Listens on 127.0.0.1 only, same Basic auth as the OpenCode server.

import http from "node:http";
import fs from "node:fs";
import { ChannelType, ActivityType } from "discord.js";

const TEXT_TYPES = new Set([ChannelType.GuildText, ChannelType.GuildAnnouncement, ChannelType.PublicThread, ChannelType.PrivateThread]);

export function startControl({ client, port, authHeader, log }) {
  const guild = (g) => {
    if (!g) {
      if (client.guilds.cache.size === 1) return client.guilds.cache.first();
      throw new Error("Several servers; pass guild (id or name).");
    }
    const found = client.guilds.cache.get(g) || client.guilds.cache.find((x) => x.name.toLowerCase() === String(g).toLowerCase());
    if (!found) throw new Error(`No server "${g}". Use action "servers".`);
    return found;
  };

  // channel: id, "name", "#name", or "Server/#name"
  const channel = async (c, g) => {
    if (!c) throw new Error("Pass channel.");
    if (/^\d{15,}$/.test(c)) return client.channels.fetch(c);
    let [gName, cName] = String(c).includes("/") ? String(c).split("/") : [g, c];
    cName = cName.replace(/^#/, "").toLowerCase();
    const guilds = gName ? [guild(gName)] : [...client.guilds.cache.values()];
    const hits = [];
    for (const gg of guilds) {
      const chans = await gg.channels.fetch();
      for (const ch of chans.values()) if (ch && TEXT_TYPES.has(ch.type) && ch.name.toLowerCase() === cName) hits.push(ch);
    }
    if (!hits.length) throw new Error(`No text channel "${c}". Use action "channels".`);
    if (hits.length > 1) throw new Error(`"${c}" is in several servers: ${hits.map((h) => `${h.guild.name}/#${h.name} (${h.id})`).join(", ")}`);
    return hits[0];
  };

  const user = async (u, g) => {
    if (/^\d{15,}$/.test(u)) return client.users.fetch(u);
    const name = String(u).replace(/^@/, "").toLowerCase();
    const guilds = g ? [guild(g)] : [...client.guilds.cache.values()];
    for (const gg of guilds) {
      const m = (await gg.members.fetch({ query: name, limit: 10 })).find(
        (x) => x.user.username.toLowerCase() === name || x.displayName.toLowerCase() === name,
      );
      if (m) return m.user;
    }
    throw new Error(`No user "${u}". Use their ID.`);
  };

  const target = async (a) => (a.user ? (await user(a.user, a.guild)).createDM() : channel(a.channel, a.guild));

  const fmt = (m) => ({
    id: m.id,
    author: m.author?.tag,
    time: m.createdAt?.toISOString(),
    content: m.content,
    attachments: [...m.attachments.values()].map((x) => x.url),
  });

  const payload = (a) => {
    const files = (a.files || []).map((f) => {
      if (!/^https?:/.test(f) && !fs.existsSync(f)) throw new Error(`File not found: ${f}`);
      return f;
    });
    if (!a.content && !files.length) throw new Error("Pass content and/or files.");
    return { content: a.content || undefined, files, allowedMentions: a.ping ? undefined : { parse: [] } };
  };

  const actions = {
    servers: async () => client.guilds.cache.map((g) => ({ id: g.id, name: g.name, members: g.memberCount })),

    channels: async (a) => {
      const g = guild(a.guild);
      const chans = await g.channels.fetch();
      return chans.filter((c) => c && TEXT_TYPES.has(c.type)).map((c) => ({ id: c.id, name: c.name, category: c.parent?.name }));
    },

    send: async (a) => {
      const ch = await target(a);
      const body = payload(a);
      const m = a.reply_to ? await (await ch.messages.fetch(a.reply_to)).reply(body) : await ch.send(body);
      return { sent: m.id, channel: ch.id };
    },

    broadcast: async (a) => {
      const body = payload(a);
      const out = [];
      for (const c of a.channels || []) {
        try {
          const ch = await channel(c, a.guild);
          out.push({ channel: c, sent: (await ch.send(body)).id });
        } catch (e) {
          out.push({ channel: c, error: e.message });
        }
      }
      return out;
    },

    read: async (a) => {
      const ch = await target(a);
      const msgs = await ch.messages.fetch({ limit: Math.min(a.limit || 20, 100) });
      return [...msgs.values()].reverse().map(fmt);
    },

    edit: async (a) => {
      const m = await (await target(a)).messages.fetch(a.message);
      await m.edit(a.content);
      return { edited: m.id };
    },

    delete: async (a) => {
      const m = await (await target(a)).messages.fetch(a.message);
      await m.delete();
      return { deleted: m.id };
    },

    bulk_delete: async (a) => {
      const ch = await channel(a.channel, a.guild);
      const n = Math.min(a.count || 0, 100);
      if (n < 1) throw new Error("Pass count (1-100).");
      const done = await ch.bulkDelete(n, true); // skips messages older than 14 days
      return { deleted: done.size };
    },

    react: async (a) => {
      const m = await (await target(a)).messages.fetch(a.message);
      await m.react(a.emoji);
      return { reacted: a.emoji };
    },

    members: async (a) => {
      const g = guild(a.guild);
      const ms = await g.members.fetch({ limit: Math.min(a.limit || 100, 1000) });
      return ms.map((m) => ({ id: m.id, username: m.user.username, name: m.displayName, bot: m.user.bot }));
    },

    status: async (a) => {
      client.user.setPresence({
        status: a.presence || "online",
        activities: a.content ? [{ name: a.content, type: ActivityType[a.activity || "Custom"] ?? ActivityType.Custom, state: a.content }] : [],
      });
      return { status: a.content || "(cleared)" };
    },
  };

  const server = http.createServer(async (req, res) => {
    const reply = (code, obj) => {
      res.writeHead(code, { "content-type": "application/json" });
      res.end(JSON.stringify(obj));
    };
    if (req.headers.authorization !== authHeader()) return reply(401, { error: "unauthorized" });
    if (req.method !== "POST" || req.url !== "/discord") return reply(404, { error: "POST /discord" });
    let raw = "";
    for await (const chunk of req) raw += chunk;
    let a;
    try {
      a = JSON.parse(raw || "{}");
    } catch {
      return reply(400, { error: "bad JSON" });
    }
    const fn = actions[a.action];
    if (!fn) return reply(400, { error: `Unknown action. Use: ${Object.keys(actions).join(", ")}` });
    if (!client.isReady()) return reply(503, { error: "Demon Dodo is not connected to Discord yet." });
    try {
      log("control", a.action, a.channel || a.user || a.guild || "");
      reply(200, { ok: true, result: await fn(a) });
    } catch (e) {
      reply(200, { ok: false, error: e.message });
    }
  });
  server.on("error", (e) => log("control server error", e.message));
  server.listen(port, "127.0.0.1", () => log(`Demon Dodo control API on 127.0.0.1:${port}`));
  return server;
}
