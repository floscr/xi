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

const projects = {
  "acme-web": {
    // bun test reports on stderr; the clj tool's sh returns stdout
    "package.json": JSON.stringify(
      { name: "acme-web", private: true, type: "module", scripts: { test: "bun test 2>&1" } },
      null, 2) + "\n",
    "README.md": "# acme-web\n\nThe Acme customer dashboard.\n",
    "AGENTS.md": "# acme-web\n\nTypeScript on Bun. Run the tests with `bun test`.\n",
    "src/upload.ts": uploadTs,
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
  '{:type :xi/config\n :version 1\n :projects {:browse ["~"]}}\n',
);
write(path.join(TOUR_HOME, ".config", "xi", "rules.edn"), "{:type :xi/rules\n :version 1\n :rules []}\n");

console.log(`Seeded tour HOME at ${TOUR_HOME}`);
console.log(`  projects: ${Object.keys(projects).join(", ")}; ${seeds.length} earlier sessions`);
console.log(`  client key: ${TOUR_CLIENT_KEY}`);
