// Demon Dodo <-> OpenCode bridge.
// Owner's Discord DMs go into the same OpenCode server (and the same session)
// that MY AI and the phone app use. Replies go back to the DM only.

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { Client, GatewayIntentBits, Partials, AttachmentBuilder } from "discord.js";

const here = path.dirname(fileURLToPath(import.meta.url));
const cfgPath = process.env.BRIDGE_CONFIG || path.join(here, "config.json");
const cfg = JSON.parse(fs.readFileSync(cfgPath, "utf8").replace(/^﻿/, "")); // PowerShell 5 writes a BOM

const BASE = (cfg.opencodeUrl || "http://127.0.0.1:4096").replace(/\/$/, "");
const DIR = cfg.directory || "C:\\Users\\Allen";
const OWNER = String(cfg.ownerId);
const SHOTS = cfg.screenshotDir || "";
const SHOTS_PER_REPLY = cfg.screenshotsPerReply ?? 1;
const UPLOADS = cfg.uploadsDir || "";
const POLL_MS = 1500;
const SYSTEM_NOTE =
  cfg.systemNote ??
  "This message came from Allen's Discord DM, not the PC or phone app. " +
    "Your final reply is sent back to that DM as plain text (Discord markdown, keep it short). " +
    "He is probably away from the PC and cannot see the screen.";

function log(...a) {
  console.log(new Date().toISOString(), ...a);
}

// ---------- OpenCode HTTP ----------

function authHeader() {
  const pw = cfg.password ?? fs.readFileSync(cfg.passwordFile, "utf8").trim();
  return "Basic " + Buffer.from(`${cfg.username || "opencode"}:${pw}`).toString("base64");
}

async function oc(method, route, body) {
  const sep = route.includes("?") ? "&" : "?";
  const url = `${BASE}${route}${sep}directory=${encodeURIComponent(DIR)}`;
  const res = await fetch(url, {
    method,
    headers: { authorization: authHeader(), "content-type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!res.ok) {
    const err = new Error(`${method} ${route} -> ${res.status} ${await res.text().catch(() => "")}`);
    err.status = res.status;
    throw err;
  }
  const text = await res.text();
  return text ? JSON.parse(text) : null;
}

// Same rule as `opencode attach -c`: continue the most recently used top-level session.
async function currentSession() {
  const list = await oc("GET", "/session");
  const top = list.filter((s) => !s.parentID).sort((a, b) => b.time.updated - a.time.updated);
  return top[0] || (await oc("POST", "/session", {}));
}

async function lastMessageId(sid) {
  const msgs = await oc("GET", `/session/${sid}/message?limit=1`);
  return msgs.length ? msgs[msgs.length - 1].info.id : "";
}

async function messagesAfter(sid, afterId) {
  const msgs = await oc("GET", `/session/${sid}/message?limit=50`);
  return msgs.filter((m) => m.info.id > afterId);
}

async function isBusy(sid) {
  const st = await oc("GET", "/session/status");
  return !!st[sid] && st[sid].type !== "idle";
}

const parentCache = new Map();
async function belongsTo(sid, root) {
  let cur = sid;
  for (let i = 0; i < 5 && cur; i++) {
    if (cur === root) return true;
    if (!parentCache.has(cur)) {
      const s = await oc("GET", `/session/${cur}`).catch(() => null);
      parentCache.set(cur, s?.parentID || null);
    }
    cur = parentCache.get(cur);
  }
  return false;
}

async function replyPermission(p, reply) {
  try {
    await oc("POST", `/permission/${p.id}/reply`, { reply });
  } catch (e) {
    if (e.status !== 404) throw e;
    await oc("POST", `/session/${p.sessionID}/permissions/${p.id}`, { response: reply }); // older servers
  }
}

// ---------- Discord helpers ----------

function chunks(text, size = 1900) {
  const out = [];
  let rest = text.trim() || "(no text reply)";
  while (rest.length > size) {
    let cut = rest.lastIndexOf("\n", size);
    if (cut < size / 2) cut = size;
    out.push(rest.slice(0, cut));
    rest = rest.slice(cut).replace(/^\n/, "");
  }
  out.push(rest);
  return out;
}

function newScreenshots(sinceMs) {
  if (!SHOTS || !SHOTS_PER_REPLY || !fs.existsSync(SHOTS)) return [];
  return fs
    .readdirSync(SHOTS)
    .filter((f) => /\.(png|jpe?g|webp)$/i.test(f))
    .map((f) => ({ f: path.join(SHOTS, f), t: fs.statSync(path.join(SHOTS, f)).mtimeMs }))
    .filter((x) => x.t >= sinceMs)
    .sort((a, b) => b.t - a.t)
    .slice(0, SHOTS_PER_REPLY)
    .map((x) => new AttachmentBuilder(x.f));
}

async function saveAttachments(msg) {
  if (!UPLOADS || !msg.attachments.size) return [];
  fs.mkdirSync(UPLOADS, { recursive: true });
  const saved = [];
  for (const a of msg.attachments.values()) {
    const name = `${Date.now()}-${a.name.replace(/[^\w.-]/g, "_")}`;
    const file = path.join(UPLOADS, name);
    const buf = Buffer.from(await (await fetch(a.url)).arrayBuffer());
    fs.writeFileSync(file, buf);
    saved.push(file);
  }
  return saved;
}

function describePermission(p) {
  const what = p.patterns?.length ? p.patterns.join("\n") : p.metadata?.command || p.metadata?.filepath || "";
  return `🔐 **Permission needed: ${p.permission}**\n${what ? "```\n" + String(what).slice(0, 1500) + "\n```\n" : ""}Reply **yes**, **always**, or **no**.`;
}

function describeQuestion(q) {
  const opts = q.options.map((o, i) => `**${i + 1}.** ${o.label}${o.description ? " — " + o.description : ""}`).join("\n");
  const how = q.multiple ? "Reply with numbers (e.g. `1 3`)" : "Reply with a number";
  return `❓ **${q.question}**\n${opts}\n${how}${q.custom !== false ? " or type your own answer." : "."}`;
}

// ---------- Bridge state ----------

let pending = null; // { kind: "permission", p } | { kind: "question", req, idx, answers }
let queue = Promise.resolve();
let running = false;
let runningSid = null;

async function handleAnswer(msg) {
  const text = msg.content.trim();
  if (pending.kind === "permission") {
    const t = text.toLowerCase();
    const reply = /^(y|yes|ok|allow|once)$/.test(t) ? "once" : /^(a|always)$/.test(t) ? "always" : /^(n|no|deny|reject)$/.test(t) ? "reject" : null;
    if (!reply) return msg.reply("Reply **yes**, **always**, or **no**.");
    const p = pending.p;
    pending = null;
    await replyPermission(p, reply);
    return msg.react(reply === "reject" ? "❌" : "✅");
  }
  const { req } = pending;
  const q = req.questions[pending.idx];
  const nums = text.split(/[\s,]+/).map(Number);
  let answer;
  if (nums.every((n) => Number.isInteger(n) && n >= 1 && n <= q.options.length)) {
    answer = (q.multiple ? nums : nums.slice(0, 1)).map((n) => q.options[n - 1].label);
  } else if (q.custom !== false) {
    answer = [text];
  } else {
    return msg.reply(`Pick 1-${q.options.length}.`);
  }
  pending.answers.push(answer);
  pending.idx++;
  if (pending.idx < req.questions.length) return msg.channel.send(describeQuestion(req.questions[pending.idx]));
  const answers = pending.answers;
  pending = null;
  await oc("POST", `/question/${req.id}/reply`, { answers });
  return msg.react("✅");
}

async function runPrompt(msg) {
  const channel = msg.channel;
  const sess = await currentSession();
  const sid = sess.id;
  const startMs = Date.now();
  const afterId = await lastMessageId(sid);
  const wasBusy = await isBusy(sid);

  const files = await saveAttachments(msg);
  let text = msg.content;
  if (files.length) text += `\n\n[Attached files saved on the PC: ${files.join(", ")}]`;
  const body = { parts: [{ type: "text", text: text || "(empty message)" }] };
  if (SYSTEM_NOTE) body.system = SYSTEM_NOTE;
  await oc("POST", `/session/${sid}/prompt_async`, body);
  log("prompt ->", sid, JSON.stringify(text.slice(0, 80)));

  running = true;
  runningSid = sid;
  const status = await channel.send(wasBusy ? "⏳ Queued behind the current task…" : "⏳ Working…");
  let statusText = "";
  const asked = new Set();
  let sawBusy = false;
  let lastTyping = 0;
  let final = null;

  try {
    for (;;) {
      await new Promise((r) => setTimeout(r, POLL_MS));
      if (Date.now() - lastTyping > 8000) {
        channel.sendTyping().catch(() => {});
        lastTyping = Date.now();
      }

      const busy = await isBusy(sid);
      if (busy) sawBusy = true;

      // Permission prompts / questions from this session or its sub-agents.
      const perms = await oc("GET", "/permission").catch(() => []);
      const qs = await oc("GET", "/question").catch(() => []);
      if (pending) {
        const id = pending.kind === "permission" ? pending.p.id : pending.req.id;
        if (![...perms, ...qs].some((x) => x.id === id)) {
          pending = null;
          await channel.send("(Answered on another device.)");
        }
      }
      if (!pending) {
        for (const p of perms) {
          if (asked.has(p.id) || !(await belongsTo(p.sessionID, sid))) continue;
          asked.add(p.id);
          pending = { kind: "permission", p };
          await channel.send(describePermission(p));
          break;
        }
      }
      if (!pending) {
        for (const q of qs) {
          if (asked.has(q.id) || !(await belongsTo(q.sessionID, sid))) continue;
          asked.add(q.id);
          pending = { kind: "question", req: q, idx: 0, answers: [] };
          await channel.send(describeQuestion(q.questions[0]));
          break;
        }
      }

      const msgs = await messagesAfter(sid, afterId);
      const assistant = msgs.filter((m) => m.info.role === "assistant");
      const last = assistant[assistant.length - 1];

      // Live step line, like the status plugin's "▶ step".
      const tool = last?.parts.filter((p) => p.type === "tool").pop();
      const line = tool ? `⏳ ${tool.tool}${tool.state?.title ? ": " + tool.state.title : ""}` : null;
      if (line && line !== statusText) {
        statusText = line;
        status.edit(line.slice(0, 1900)).catch(() => {});
      }

      if (!busy && (sawBusy || Date.now() - startMs > 5000) && last?.info.time?.completed) {
        final = last;
        break;
      }
      if (!busy && !sawBusy && Date.now() - startMs > 30000) break; // never started
    }
  } finally {
    running = false;
    runningSid = null;
    if (pending) {
      pending = null;
      channel.send("(That prompt was answered elsewhere or expired.)").catch(() => {});
    }
    status.delete().catch(() => {});
  }

  if (!final) return channel.send("⚠️ OpenCode didn't start on that. Check the PC.");
  if (final.info.error) {
    const e = final.info.error;
    const why = e.name === "MessageAbortedError" ? "Stopped." : `⚠️ ${e.name}: ${e.data?.message || ""}`;
    return channel.send(why.slice(0, 1900));
  }
  const reply = final.parts
    .filter((p) => p.type === "text" && !p.synthetic)
    .map((p) => p.text)
    .join("\n");
  const parts = chunks(reply);
  const shots = newScreenshots(startMs);
  for (let i = 0; i < parts.length; i++) {
    await channel.send({ content: parts[i], files: i === parts.length - 1 ? shots : [] });
  }
}

async function handleCommand(msg) {
  const cmd = msg.content.trim().toLowerCase();
  if (cmd === "!new") {
    const s = await oc("POST", "/session", {});
    return msg.reply(`New chat started (\`${s.id}\`). The phone and MY AI will continue this one too.`);
  }
  if (cmd === "!stop") {
    const sid = runningSid || (await currentSession()).id;
    await oc("POST", `/session/${sid}/abort`);
    return msg.react("🛑");
  }
  if (cmd === "!session") {
    const s = await currentSession();
    return msg.reply(`Current chat: **${s.title}** (\`${s.id}\`)`);
  }
  if (cmd === "!help") {
    return msg.reply("`!new` new chat · `!stop` stop the current task · `!session` show current chat");
  }
  return null;
}

// ---------- Discord client ----------

const client = new Client({
  intents: [GatewayIntentBits.DirectMessages, GatewayIntentBits.Guilds],
  partials: [Partials.Channel, Partials.Message],
});

client.once("clientReady", () => log(`Discord bridge online as ${client.user.tag}; owner ${OWNER}; OpenCode ${BASE}`));

client.on("messageCreate", async (msg) => {
  if (msg.author.bot || msg.guild) return; // DMs only
  if (msg.author.id !== OWNER) return log("ignored DM from", msg.author.tag, msg.author.id);
  try {
    if (pending) return await handleAnswer(msg);
    if (msg.content.trim().startsWith("!") && (await handleCommand(msg)) !== null) return;
    if (running) await msg.react("🕒").catch(() => {});
    queue = queue.then(() => runPrompt(msg)).catch(async (e) => {
      log("prompt error", e);
      await msg.channel.send(`⚠️ ${e.message.includes("fetch failed") ? "Can't reach OpenCode on the PC." : e.message}`.slice(0, 1900)).catch(() => {});
    });
  } catch (e) {
    log("error", e);
    msg.channel.send(`⚠️ ${e.message}`.slice(0, 1900)).catch(() => {});
  }
});

process.on("unhandledRejection", (e) => log("unhandledRejection", e));
client.login(cfg.discordToken);
