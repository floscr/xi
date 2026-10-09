#!/usr/bin/env bun
/*
 * Record a site tour headlessly: connect to the tour server (`bb tour <tour>`,
 * port 7479) as a web client, play the client's side from
 * site/tours/<tour>/record.mjs and capture every frame both ways, then bake
 * the tape (scripts/tour-bake.mjs).
 *
 *   bun scripts/tour-record.mjs <tour>
 *
 * No browser: the tape only needs the server's frames, and the replaying web
 * client (xi.web.tour) sends the same events again. record.mjs default-exports
 * async ({ send, until, sleep, cwd }) => …; `send` takes a flat map whose
 * strings starting with ":" are keywords, `until` resolves to the first frame
 * (raw transit) containing every given substring.
 */
import fs from "node:fs";
import path from "node:path";
import { execFileSync } from "node:child_process";

const REPO = path.resolve(import.meta.dir, "..");
// Same path as scripts/tour-seed.mjs and tour-home in bb.edn.
const TOUR_HOME = "/tmp/xi-tour-home";
const KEY = fs.readFileSync(path.join(TOUR_HOME, ".config", "xi", "client-key"), "utf8").trim();

const tour = process.argv[2] || "desk";
const { default: play } = await import(path.join(REPO, "site", "tours", tour, "record.mjs"));

// ── transit for flat maps (the client's sends) ───────────────────────────────
const value = (v) =>
  typeof v !== "string" ? v
    : v.startsWith(":") ? "~" + v
    : /^[~^`]/.test(v) ? "~" + v
    : v;
const encode = (m) =>
  JSON.stringify(["^ ", ...Object.entries(m).flatMap(([k, v]) => ["~:" + k, value(v)])]);

// ── record ───────────────────────────────────────────────────────────────────
const ws = new WebSocket("ws://127.0.0.1:7479");
const t0 = performance.now();
const now = () => Math.round(performance.now() - t0);
const frames = [];
const waiters = [];

ws.addEventListener("message", (e) => {
  const data = String(e.data);
  frames.push([now(), "in", data]);
  for (const w of [...waiters]) {
    if (w.parts.every((p) => data.includes(p))) {
      waiters.splice(waiters.indexOf(w), 1);
      w.resolve(data);
    }
  }
});

const send = (m) => {
  const data = encode(m);
  frames.push([now(), "out", data]);
  ws.send(data);
};
const until = (...parts) =>
  new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`timed out waiting for ${parts.join(" + ")}`)), 30000);
    waiters.push({ parts, resolve: (d) => { clearTimeout(timer); resolve(d); } });
  });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

await new Promise((resolve, reject) => {
  ws.addEventListener("open", resolve);
  ws.addEventListener("error", () => reject(new Error("no tour server on :7479 — run `bb tour " + tour + "`")));
});
send({ type: ":auth/hello", "client-key": KEY, "client-name": "Linux (web)", platform: "web" });
await until('"~:auth/ok"');

await play({ send, until, sleep, cwd: (project) => path.join(TOUR_HOME, project) });
await sleep(800);
ws.close();

// raw frames still carry the tour HOME path and the client key: keep them out
// of the repo, only the baked tape ships
const raw = path.join("/tmp", `xi-tour-${tour}-raw.json`);
fs.writeFileSync(raw, JSON.stringify({ recordedAt: Date.now() - now(), frames }));
console.log(`Recorded ${frames.length} frames → ${raw}`);
execFileSync("bun", [path.join(REPO, "scripts", "tour-bake.mjs"), raw, tour], { stdio: "inherit" });
