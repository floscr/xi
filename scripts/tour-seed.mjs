#!/usr/bin/env bun
/*
 * Build the throwaway HOME used by `bb tour` to record site tours.
 *
 * Like scripts/demo-seed.mjs, but nothing is shared with the real HOME: the
 * server runs against the scripted fake LLM (XI_FAKE_LLM), so no credentials
 * are needed. The project the tour works in is real, so the fake's read /
 * edit / clj calls run for real and their output is genuine.
 *
 *   acme-web/                            the project (git repo, bun tests)
 *   acme-api/                            a second project for the sidebar
 *   .config/xi/sessions, .claude/projects  earlier sessions for the sidebar
 *   .config/xi/client-key                the local key: trusted, no pairing
 *
 * The HOME is /tmp/xi-tour-home, outside this repo: xi looks for AGENTS.md
 * files up the tree, and inside the repo it would load (and the tape would
 * carry) Xi's own. scripts/tour-bake.mjs rewrites the path to /home/dev, so
 * the cwds read as /home/dev/acme-web on the site. Idempotent: wipes and
 * rebuilds.
 */
import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import { execFileSync } from "node:child_process";

// Same path as tour-home in bb.edn and scripts/tour-bake.mjs.
const TOUR_HOME = "/tmp/xi-tour-home";

export const TOUR_CLIENT_KEY =
  "tour00000000000000000000000000000000000000000000000000000000tour";

// ── path encoders (mirror xi.session / xi.session.sync) ──────────────────────
const encXi = (cwd) => "-" + cwd.replace(/^\//, "").replace(/\//g, "-");
const encClaude = (cwd) => "-" + cwd.replace(/^\//, "").replace(/[/.]/g, "-");

const uuid = () => crypto.randomUUID();
const iso = (msAgo) => new Date(Date.now() - msAgo).toISOString();
const mins = (m) => m * 60000;

const msg = (type, cwd, sid, content, msAgo) => ({
  type,
  uuid: uuid(),
  sessionId: sid,
  cwd,
  timestamp: iso(msAgo),
  message: { role: type, content },
});

const rmrf = (p) => fs.rmSync(p, { recursive: true, force: true });
const mkdirp = (p) => fs.mkdirSync(p, { recursive: true });
const write = (p, s) => { mkdirp(path.dirname(p)); fs.writeFileSync(p, s); };
const writeJson = (p, obj) => write(p, JSON.stringify(obj, null, 2) + "\n");

// ── the project ──────────────────────────────────────────────────────────────
const uploadTs = `export interface RetryOptions {
  attempts: number;
  baseMs: number;
}

export const defaults: RetryOptions = { attempts: 8, baseMs: 500 };

export function backoffDelay(attempt: number, { baseMs }: RetryOptions): number {
  return baseMs * 2 ** attempt;
}

export async function upload(file: Blob, url: string, opts = defaults) {
  for (let attempt = 0; ; attempt++) {
    try {
      const res = await fetch(url, { method: "PUT", body: file });
      if (res.ok) return res;
      throw new Error(\`upload failed: \${res.status}\`);
    } catch (err) {
      if (attempt + 1 >= opts.attempts) throw err;
      await Bun.sleep(backoffDelay(attempt, opts));
    }
  }
}
`;

// The other modules, for the scene tour's size chart: self-contained (no
// imports), so each one's bundle is its own size, clearly apart.
const chartsTs = `export type Series = { name: string; points: number[] };

export const palette = [
  "#4f46e5", "#0ea5e9", "#10b981", "#f59e0b", "#ef4444", "#8b5cf6",
  "#14b8a6", "#f97316", "#84cc16", "#ec4899", "#6366f1", "#22c55e",
];

const units = ["", "k", "M", "B", "T"];

export function compact(n: number): string {
  let i = 0;
  while (Math.abs(n) >= 1000 && i < units.length - 1) { n /= 1000; i++; }
  return (Math.round(n * 10) / 10).toString() + units[i];
}

export function niceTicks(min: number, max: number, count = 5): number[] {
  const span = max - min || 1;
  const step0 = span / count;
  const mag = 10 ** Math.floor(Math.log10(step0));
  const step = [1, 2, 2.5, 5, 10].map((m) => m * mag).find((s) => span / s <= count) ?? mag * 10;
  const ticks: number[] = [];
  for (let v = Math.floor(min / step) * step; v <= max + step / 2; v += step) ticks.push(v);
  return ticks;
}

function scale(domain: [number, number], range: [number, number]) {
  const [d0, d1] = domain, [r0, r1] = range;
  return (v: number) => r0 + ((v - d0) / (d1 - d0 || 1)) * (r1 - r0);
}

function el(tag: string, attrs: Record<string, string | number>, children: string[] = []) {
  const a = Object.entries(attrs).map(([k, v]) => \` \${k}="\${v}"\`).join("");
  return \`<\${tag}\${a}>\${children.join("")}</\${tag}>\`;
}

export function lineChart(series: Series[], width = 640, height = 320): string {
  const pad = { top: 16, right: 16, bottom: 28, left: 48 };
  const all = series.flatMap((s) => s.points);
  const ticks = niceTicks(Math.min(0, ...all), Math.max(...all));
  const n = Math.max(...series.map((s) => s.points.length));
  const x = scale([0, n - 1], [pad.left, width - pad.right]);
  const y = scale([ticks[0], ticks[ticks.length - 1]], [height - pad.bottom, pad.top]);
  const grid = ticks.map((t) =>
    el("g", {}, [
      el("line", { x1: pad.left, x2: width - pad.right, y1: y(t), y2: y(t), stroke: "#8884" }),
      el("text", { x: pad.left - 8, y: y(t) + 4, "text-anchor": "end", "font-size": 11 }, [compact(t)]),
    ]));
  const lines = series.map((s, i) =>
    el("polyline", {
      fill: "none",
      stroke: palette[i % palette.length],
      "stroke-width": 2,
      points: s.points.map((p, j) => \`\${x(j)},\${y(p)}\`).join(" "),
    }));
  return el("svg", { viewBox: \`0 0 \${width} \${height}\`, role: "img" }, [...grid, ...lines]);
}

export function barChart(values: Record<string, number>, width = 640, height = 320): string {
  const pad = { top: 16, right: 16, bottom: 28, left: 48 };
  const entries = Object.entries(values);
  const ticks = niceTicks(0, Math.max(...entries.map(([, v]) => v)));
  const y = scale([0, ticks[ticks.length - 1]], [height - pad.bottom, pad.top]);
  const band = (width - pad.left - pad.right) / entries.length;
  const bars = entries.map(([label, v], i) =>
    el("g", {}, [
      el("rect", {
        x: pad.left + i * band + band * 0.15,
        y: y(v),
        width: band * 0.7,
        height: height - pad.bottom - y(v),
        fill: palette[i % palette.length],
        rx: 3,
      }),
      el("text", { x: pad.left + (i + 0.5) * band, y: height - 8, "text-anchor": "middle", "font-size": 11 }, [label]),
    ]));
  return el("svg", { viewBox: \`0 0 \${width} \${height}\`, role: "img" }, bars);
}

export function sparkline(points: number[], width = 120, height = 24): string {
  const x = scale([0, points.length - 1], [0, width]);
  const y = scale([Math.min(...points), Math.max(...points)], [height, 0]);
  return el("svg", { viewBox: \`0 0 \${width} \${height}\` }, [
    el("polyline", { fill: "none", stroke: palette[0], points: points.map((p, i) => \`\${x(i)},\${y(p)}\`).join(" ") }),
  ]);
}
`;

const dashboardTs = `export type Project = { id: string; name: string; updatedAt: number; members: number };

export type Filter = { query: string; sort: "name" | "recent" | "members" };

export function visible(projects: Project[], { query, sort }: Filter): Project[] {
  const q = query.trim().toLowerCase();
  const hits = q ? projects.filter((p) => p.name.toLowerCase().includes(q)) : projects;
  const by = {
    name: (a: Project, b: Project) => a.name.localeCompare(b.name),
    recent: (a: Project, b: Project) => b.updatedAt - a.updatedAt,
    members: (a: Project, b: Project) => b.members - a.members,
  }[sort];
  return [...hits].sort(by);
}

export function ago(ms: number, now = Date.now()): string {
  const s = Math.round((now - ms) / 1000);
  if (s < 60) return "just now";
  if (s < 3600) return \`\${Math.round(s / 60)}m ago\`;
  if (s < 86400) return \`\${Math.round(s / 3600)}h ago\`;
  return \`\${Math.round(s / 86400)}d ago\`;
}

export function card(p: Project): string {
  return \`<li class="card"><h3>\${p.name}</h3><p>\${p.members} members · \${ago(p.updatedAt)}</p></li>\`;
}

export function render(projects: Project[], filter: Filter): string {
  const list = visible(projects, filter);
  if (list.length === 0) return '<p class="empty">No projects yet. <button>New project</button></p>';
  return \`<ul class="cards">\${list.map(card).join("")}</ul>\`;
}
`;

const authTs = `export type Session = { token: string; expiresAt: number };

export function expired(s: Session | null, now = Date.now()): boolean {
  return !s || s.expiresAt - 30_000 <= now;
}

export async function login(email: string, password: string): Promise<Session> {
  const res = await fetch("/api/login", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ email, password }),
  });
  if (!res.ok) throw new Error(res.status === 429 ? "Too many attempts" : "Wrong email or password");
  return res.json();
}
`;

const formatTs = `export const money = (cents: number, currency = "EUR") =>
  new Intl.NumberFormat("en", { style: "currency", currency }).format(cents / 100);

export const percent = (x: number) => \`\${Math.round(x * 100)}%\`;
`;

const projects = {
  "acme-web": {
    // bun test reports on stderr; the clj tool's sh returns stdout. `size`
    // bundles each module on its own (the scene tour)
    "package.json": JSON.stringify(
      { name: "acme-web", private: true, type: "module",
        scripts: { test: "bun test 2>&1",
                   size: "bun build src/*.ts --outdir dist --minify 2>&1" } },
      null, 2) + "\n",
    "README.md": "# acme-web\n\nThe Acme customer dashboard.\n",
    "AGENTS.md": "# acme-web\n\nTypeScript on Bun. Run the tests with `bun test`.\n",
    ".gitignore": "dist/\n",
    "src/upload.ts": uploadTs,
    "src/charts.ts": chartsTs,
    "src/dashboard.ts": dashboardTs,
    "src/auth.ts": authTs,
    "src/format.ts": formatTs,
  },
  "acme-api": {
    "README.md": "# acme-api\n\nThe Acme HTTP API.\n",
  },
};

const git = (cwd, ...args) =>
  execFileSync("git", args, {
    cwd,
    stdio: "ignore",
    env: {
      ...process.env,
      GIT_AUTHOR_NAME: "Dev", GIT_AUTHOR_EMAIL: "dev@example.com",
      GIT_COMMITTER_NAME: "Dev", GIT_COMMITTER_EMAIL: "dev@example.com",
    },
  });

// ── earlier sessions, for the sidebar ────────────────────────────────────────
const seeds = [
  { project: "acme-web", name: "Dashboard empty state", ageMins: 25,
    prompt: "The dashboard shows a blank card when there are no projects. Add an empty state.",
    reply: "Added an `<EmptyState>` with a short hint and a **New project** button. It renders when the list is empty." },
  { project: "acme-api", name: "Fix flaky auth tests", ageMins: 70,
    prompt: "The auth integration tests are flaky in CI. Find out why.",
    reply: "A shared `Date.now()` stub leaked between tests. Each test now uses its own fake timers; 20 runs, all green." },
  { project: "acme-web", name: "Bump TypeScript to 5.9", ageMins: 180,
    prompt: "Upgrade TypeScript to 5.9 and fix whatever breaks.",
    reply: "Upgraded. Two `satisfies` checks needed a wider type; everything else compiled as is." },
  { project: "acme-api", name: "Rate-limit the login route", ageMins: 1500,
    prompt: "Add a rate limit to POST /login.",
    reply: "Added a sliding-window limiter: 10 attempts per minute per IP, with a `Retry-After` header on 429." },
];

// ── build ─────────────────────────────────────────────────────────────────────
rmrf(TOUR_HOME);
mkdirp(TOUR_HOME);

for (const [name, files] of Object.entries(projects)) {
  const dir = path.join(TOUR_HOME, name);
  for (const [rel, content] of Object.entries(files)) write(path.join(dir, rel), content);
  git(dir, "init", "-q", "-b", "main");
  git(dir, "add", ".");
  git(dir, "commit", "-q", "-m", "Initial commit");
}

for (const s of seeds) {
  const cwd = path.join(TOUR_HOME, s.project);
  const sid = uuid();
  writeJson(path.join(TOUR_HOME, ".config", "xi", "sessions", encXi(cwd), `${sid}.json`), {
    id: sid,
    "cli-session-id": sid,
    cwd,
    created: iso(mins(s.ageMins)),
    "last-accessed": iso(mins(s.ageMins)),
    name: s.name,
    model: "claude-opus-4-6",
    "personal-agent?": false,
  });
  write(
    path.join(TOUR_HOME, ".claude", "projects", encClaude(cwd), `${sid}.jsonl`),
    [msg("user", cwd, sid, s.prompt, mins(s.ageMins + 2)),
     msg("assistant", cwd, sid, [{ type: "text", text: s.reply }], mins(s.ageMins))]
      .map((o) => JSON.stringify(o)).join("\n") + "\n",
  );
}

write(path.join(TOUR_HOME, ".config", "xi", "client-key"), TOUR_CLIENT_KEY + "\n");
write(
  path.join(TOUR_HOME, ".config", "xi", "config.edn"),
  // link hints have no default key; the release tours show them on `f`. The
  // scene demo (scene_show, a three.js app view) is the hero tour's block
  '{:type :xi/config\n :version 1\n :projects {:browse ["~"]}\n :demo-extensions ["scene.cljs"]\n :keys {:web {:mode/navigate {"f" :hints/show}}}}\n',
);
write(path.join(TOUR_HOME, ".config", "xi", "rules.edn"), "{:type :xi/rules\n :version 1\n :rules []}\n");
// The terminal tours' shell: a prompt without user or host name, no
// end-of-line marks, no new-user wizard. zsh reads $ZDOTDIR/.zshrc, which
// a system profile may point at $XDG_CONFIG_HOME/zsh (NixOS dotfiles do), else
// $HOME/.zshrc.
const zshrc = "PROMPT='%F{blue}%~%f $ '\nunsetopt PROMPT_SP\n";
write(path.join(TOUR_HOME, ".zshrc"), zshrc);
write(path.join(TOUR_HOME, ".config", "zsh", ".zshrc"), zshrc);
write(path.join(TOUR_HOME, ".bashrc"), "PS1='\\w $ '\n");

console.log(`Seeded tour HOME at ${TOUR_HOME}`);
console.log(`  projects: ${Object.keys(projects).join(", ")}; ${seeds.length} earlier sessions`);
console.log(`  client key: ${TOUR_CLIENT_KEY}`);
