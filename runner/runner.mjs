#!/usr/bin/env node
// xi-claude-runner — standalone Claude Agent SDK runner.
//
// Isolates @anthropic-ai/claude-agent-sdk in its own process with its own
// node_modules, so Xi's build isn't chained to one SDK version. Xi (the host)
// spawns one of these per turn and speaks newline-delimited JSON over stdio:
//
//   host → runner (stdin):
//     {type:"start", queryOpts, envOverride, toolDefs, prompt, noTools}
//     {type:"tool-result", id, result:{content, isError}}
//     {type:"abort"}
//
//   runner → host (stdout):
//     {type:"sdk-message", message}   raw SDK message, forwarded verbatim
//     {type:"tool-call", id, name, arguments}
//     {type:"done"}
//     {type:"error", message}
//
// The runner is thin: it forwards SDK messages (the host decodes them) and
// proxies each tool call back to the host (where the permission gate +
// registry live). It never touches the host's data.

import { query, createSdkMcpServer } from "@anthropic-ai/claude-agent-sdk";
import { z } from "zod";
import { execSync } from "node:child_process";
import { realpathSync } from "node:fs";

// ── Framing ──────────────────────────────────────────────────────────────────
// Protocol frames go to stdout; anything else (diagnostics) must go to stderr
// so the frame stream stays clean.

function send(obj) {
  let line;
  try {
    line = JSON.stringify(obj);
  } catch (e) {
    line = JSON.stringify({ type: "error", message: "serialize failed: " + String(e) });
  }
  try {
    process.stdout.write(line + "\n");
  } catch (e) {
    process.stderr.write("[runner] stdout write failed: " + String(e) + "\n");
  }
}

// The host kills us once it has the terminal frame; a write racing that close
// must not become an uncaughtException (which would itself try to `send`).
process.stdout.on("error", (e) => {
  process.stderr.write("[runner] stdout error: " + String((e && e.message) || e) + "\n");
});

// Set once the turn's terminal frame (`done` / `error`) is out. Late errors —
// e.g. the SDK's transport EPIPE-ing into a CLI child that already exited —
// are logged to stderr instead of being sent as a second, misleading error.
let turnEnded = false;

function sendTerminal(frame) {
  if (turnEnded) return;
  turnEnded = true;
  send(frame);
}

function lateError(prefix, e) {
  const message = prefix + String((e && e.message) || e);
  if (turnEnded) process.stderr.write("[runner] " + message + "\n");
  else sendTerminal({ type: "error", message });
}

// ── JSON Schema → Zod (sole copy — Xi ships tool defs as plain JSON Schema) ─────────────────────────────

function parseJsonString(v) {
  if (typeof v === "string") {
    try { return JSON.parse(v); } catch { return v; }
  }
  return v;
}

function propToZod(prop) {
  const t = prop.type;
  const en = prop.enum;
  let base;
  if (en && en.length) base = z.enum(en);
  else if (t === "string") base = z.string();
  else if (t === "number" || t === "integer") base = z.number();
  else if (t === "boolean") base = z.boolean();
  else if (t === "array")
    base = z.preprocess(parseJsonString,
      prop.items ? z.array(propToZod(prop.items)) : z.array(z.unknown()));
  else if (t === "object")
    base = z.preprocess(parseJsonString,
      prop.properties ? z.object(shapeOf(prop)) : z.record(z.string(), z.unknown()));
  else base = z.unknown();
  if (prop.description) base = base.describe(prop.description);
  return base;
}

function shapeOf(schema) {
  const props = schema.properties || {};
  const req = new Set(schema.required || []);
  const shape = {};
  for (const k of Object.keys(props)) {
    const zp = propToZod(props[k]);
    shape[k] = req.has(k) ? zp : zp.optional();
  }
  return shape;
}

function schemaToShape(schema) {
  if (!schema || !schema.properties) return {};
  return shapeOf(schema);
}

// ── Tool bridge — proxy each call back to the host ─────────────────────────────

let toolSeq = 0;
const pendingTools = new Map(); // id → resolve fn

function buildMcpServer(toolDefs) {
  const tools = toolDefs.map((def) => ({
    name: def.name,
    description: def.description,
    inputSchema: schemaToShape(def.input_schema),
    handler: (args, _extra) =>
      new Promise((resolve) => {
        const id = "t" + (++toolSeq);
        pendingTools.set(id, resolve);
        send({ type: "tool-call", id, name: def.name, arguments: args });
      }),
  }));
  return createSdkMcpServer({ name: "xi-tools", version: "1.0.0", tools });
}

// ── Claude CLI resolution ──────────────────────────────────────────────────────
// SDK ≥0.2.12x no longer bundles cli.js; it ships a native `claude` binary in
// @anthropic-ai/claude-agent-sdk-<platform>. That generic-linux ELF cannot run
// on NixOS ("Could not start dynamically linked executable" → the SDK sees
// exit 127 and then EPIPEs writing to the dead child). So prefer the `claude`
// on PATH — the SDK accepts both a `.js` entrypoint and a native binary as
// pathToClaudeCodeExecutable — and only fall back to the SDK's own binary
// when there is none. XI_CLAUDE_CLI_PATH overrides the lookup.

function resolveClaudeExecutable() {
  const override = process.env.XI_CLAUDE_CLI_PATH;
  if (override) return override;
  try {
    const w = execSync("which claude", { encoding: "utf8" }).trim();
    return w ? realpathSync(w) : null;
  } catch {
    return null;
  }
}

// ── Turn execution ─────────────────────────────────────────────────────────────

let currentQuery = null;

function buildPrompt(prompt) {
  if (prompt && typeof prompt === "object" && Array.isArray(prompt.blocks)) {
    const content = [];
    if (prompt.text) content.push({ type: "text", text: prompt.text });
    for (const b of prompt.blocks) {
      if (b.media_type === "application/pdf")
        content.push({ type: "document", source: { type: "base64", media_type: b.media_type, data: b.data } });
      else
        content.push({ type: "image", source: { type: "base64", media_type: b.media_type, data: b.data } });
    }
    const msg = { type: "user", message: { role: "user", content }, parent_tool_use_id: null };
    return (async function* () { yield msg; })();
  }
  return prompt; // plain string
}

async function runTurn({ queryOpts, envOverride, toolDefs, prompt, noTools }) {
  const opts = { ...queryOpts };
  opts.env = { ...process.env, ...(envOverride || {}) };
  const cli = resolveClaudeExecutable();
  if (cli) opts.pathToClaudeCodeExecutable = cli;
  if (!noTools && toolDefs && toolDefs.length) {
    opts.mcpServers = { "xi-tools": buildMcpServer(toolDefs) };
  }

  const q = query({ prompt: buildPrompt(prompt), options: opts });
  currentQuery = q;
  try {
    for await (const message of q) {
      send({ type: "sdk-message", message });
    }
    sendTerminal({ type: "done" });
  } catch (err) {
    sendTerminal({ type: "error", message: String((err && err.message) || err) });
  } finally {
    try { await q.close?.(); } catch { }
    currentQuery = null;
  }
}

async function abort() {
  const q = currentQuery;
  if (!q) return;
  try { await q.interrupt?.(); } catch { }
  try { await q.close?.(); } catch { }
}

// ── stdin frame reader ─────────────────────────────────────────────────────────

let buf = "";
process.stdin.setEncoding("utf8");
process.stdin.on("data", (chunk) => {
  buf += chunk;
  let nl;
  while ((nl = buf.indexOf("\n")) >= 0) {
    const line = buf.slice(0, nl);
    buf = buf.slice(nl + 1);
    if (!line.trim()) continue;
    let frame;
    try { frame = JSON.parse(line); } catch (e) {
      send({ type: "error", message: "bad frame: " + String(e) });
      continue;
    }
    handleFrame(frame);
  }
});

function handleFrame(frame) {
  switch (frame.type) {
    case "start":
      runTurn(frame);
      break;
    case "tool-result": {
      const resolve = pendingTools.get(frame.id);
      if (resolve) {
        pendingTools.delete(frame.id);
        const r = frame.result || {};
        resolve({ content: r.content, isError: r.isError });
      }
      break;
    }
    case "abort":
      abort();
      break;
    default:
      send({ type: "error", message: "unknown frame type: " + String(frame.type) });
  }
}

process.on("uncaughtException", (e) => lateError("uncaught: ", e));
process.on("unhandledRejection", (e) => lateError("unhandled: ", e));
