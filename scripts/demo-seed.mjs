#!/usr/bin/env bun
/*
 * Build the isolated demo HOME used by `bb demo`.
 *
 * Everything xi reads derives from $HOME (sessions in ~/.config/xi, transcripts
 * in ~/.claude/projects, client auth in ~/.config/xi/clients.edn). So the demo
 * server is launched with HOME pointed at the tree this script builds — it can
 * never see or mutate the real active session.
 *
 * What we isolate (fresh, seeded):
 *   .config/xi/sessions/<enc>/*.json     — Xi session metadata
 *   .claude/projects/<enc>/*.jsonl       — matching Claude transcripts
 *   .config/xi/clients.edn               — pre-approved demo client key (no pairing)
 *
 * What we share from the real HOME so live Claude turns still work:
 *   .claude/.credentials.json    → symlink  (Claude CLI OAuth; refresh stays shared)
 *   .claude/settings.json        → symlink  (if present)
 *   .claude/CLAUDE.md            → symlink  (global instructions; if present)
 *   .claude.json                 → copy     (config; demo writes stay contained)
 *
 * Idempotent: wipes and rebuilds .demo-home/ every run.
 */
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import crypto from "node:crypto";

const REPO = path.resolve(import.meta.dir, "..");
const REAL_HOME = os.homedir();
const DEMO_HOME = path.join(REPO, ".demo-home");

// A fixed, obviously-fake key so the browser connects without the pairing
// dance. `bb demo` prints the localStorage snippet that installs it.
export const DEMO_CLIENT_KEY =
  "demo00000000000000000000000000000000000000000000000000000000demo";

// ── path encoders (mirror xi.session / xi.session.sync) ──────────────────────
const encXi = (cwd) => "-" + cwd.replace(/^\//, "").replace(/\//g, "-");
const encClaude = (cwd) => "-" + cwd.replace(/^\//, "").replace(/[/.]/g, "-");

const uuid = () => crypto.randomUUID();
const iso = (msAgo) => new Date(Date.now() - msAgo).toISOString();
const mins = (m) => m * 60000;

// ── transcript block builders ────────────────────────────────────────────────
const userMsg = (cwd, sid, text, msAgo) => ({
  type: "user",
  uuid: uuid(),
  sessionId: sid,
  cwd,
  timestamp: iso(msAgo),
  message: { role: "user", content: text },
});
const userBlocks = (cwd, sid, blocks, msAgo) => ({
  type: "user",
  uuid: uuid(),
  sessionId: sid,
  cwd,
  timestamp: iso(msAgo),
  message: { role: "user", content: blocks },
});
const asstMsg = (cwd, sid, blocks, msAgo) => ({
  type: "assistant",
  uuid: uuid(),
  sessionId: sid,
  cwd,
  timestamp: iso(msAgo),
  message: { role: "assistant", content: blocks },
});
const toolUse = (id, name, input) => ({ type: "tool_use", id, name, input });
const toolResult = (id, content, isError = false) => ({
  type: "tool_result",
  tool_use_id: id,
  content,
  is_error: isError,
});
const text = (t) => ({ type: "text", text: t });

// ── seed content ──────────────────────────────────────────────────────────────
const readResult = `export function Settings({ user }: SettingsProps) {
  const { theme } = useTheme();
  return (
    <section className="settings">
      <h1>Settings</h1>
      <ProfileForm user={user} />
      <NotificationPrefs />
    </section>
  );
}`;

const editResult = `  export function Settings({ user }: SettingsProps) {
-   const { theme } = useTheme();
+   const { theme, toggleTheme } = useTheme();
    return (
      <section className="settings">
        <h1>Settings</h1>
+       <ThemeToggle checked={theme === "dark"} onChange={toggleTheme} />
        <ProfileForm user={user} />
        <NotificationPrefs />
      </section>
    );
  }`;

const testResult = ` PASS  src/components/ThemeToggle.test.tsx
  ThemeToggle
    \u2713 renders in the light state (18 ms)
    \u2713 toggles to dark on click (7 ms)
    \u2713 persists the choice to localStorage (5 ms)

Test Suites: 1 passed, 1 total
Tests:       3 passed, 3 total
Time:        1.284 s`;

const darkModeFinal = `Done \u2014 the settings page now has a dark mode toggle.

**What changed**

- \`useTheme()\` now also returns \`toggleTheme\`, wired to the existing \`ThemeContext\`.
- Added a \`<ThemeToggle>\` control to the settings section.
- The choice persists to \`localStorage\` so it survives reloads.

All three tests pass. Want me to add a keyboard shortcut (\`\u2318\u21e7D\`) for it too?`;

const grepResult = `src/api/auth.test.ts:41:  it("rejects an expired token", async () => {
src/api/auth.test.ts:58:  it("refreshes a near-expiry token", async () => {
src/api/auth.test.ts:72:  it("logs the user out on a revoked token", async () => {`;

const authFinal = `The flakiness was a shared \`Date.now()\` stub leaking between tests. I switched
the token-expiry suite to fake timers scoped per-test with \`jest.useFakeTimers()\`
in a \`beforeEach\` / \`afterEach\`, so the three auth tests no longer race.

Ran the suite 20x locally \u2014 green every time.`;

/**
 * Each seed: { cwd, name, model, ageMins, lines(cwd, sid) => transcript objs }
 */
const seeds = [
  {
    cwd: "/home/demo/acme-web",
    name: "Add dark mode toggle",
    model: "claude-opus-4-6",
    ageMins: 3,
    favorite: true,
    lines: (cwd, sid) => [
      userMsg(cwd, sid, "Add a dark mode toggle to the settings page. It should persist the user's choice.", mins(9)),
      asstMsg(cwd, sid, [
        text("I'll start by reading the current settings page to see how theming is set up."),
        toolUse("tu_read", "Read", { file_path: "src/components/Settings.tsx" }),
      ], mins(8)),
      userBlocks(cwd, sid, [toolResult("tu_read", readResult)], mins(8)),
      asstMsg(cwd, sid, [
        text("The page pulls `theme` from `useTheme()` but never lets the user change it. I'll expose `toggleTheme` and drop a `ThemeToggle` into the settings section."),
        toolUse("tu_edit", "Edit", { file_path: "src/components/Settings.tsx" }),
      ], mins(7)),
      userBlocks(cwd, sid, [toolResult("tu_edit", editResult)], mins(7)),
      asstMsg(cwd, sid, [toolUse("tu_test", "Bash", { command: "npm test -- ThemeToggle" })], mins(6)),
      userBlocks(cwd, sid, [toolResult("tu_test", testResult)], mins(6)),
      asstMsg(cwd, sid, [text(darkModeFinal)], mins(5)),
    ],
  },
  {
    cwd: "/home/demo/acme-api",
    name: "Fix flaky auth integration tests",
    model: "claude-opus-4-6",
    ageMins: 52,
    lines: (cwd, sid) => [
      userMsg(cwd, sid, "The auth integration tests are flaky in CI. Figure out why and fix them.", mins(60)),
      asstMsg(cwd, sid, [
        text("Let me find the auth tests first."),
        toolUse("tu_grep", "Grep", { pattern: "token", glob: "*.test.ts" }),
      ], mins(59)),
      userBlocks(cwd, sid, [toolResult("tu_grep", grepResult)], mins(59)),
      asstMsg(cwd, sid, [text(authFinal)], mins(57)),
    ],
  },
  {
    cwd: "/home/demo/acme-api",
    name: "Profile slow dashboard query",
    model: "claude-sonnet-4-6",
    ageMins: 180,
    lines: (cwd, sid) => [
      userMsg(cwd, sid, "The dashboard query takes ~4s. Profile it and suggest an index.", mins(185)),
      asstMsg(cwd, sid, [
        text("A composite index on `(org_id, created_at)` collapses the seq scan the planner is doing on `events`. That drops the query from ~3.9s to ~40ms in EXPLAIN ANALYZE. Want me to write the migration?"),
      ], mins(182)),
    ],
  },
  {
    cwd: "/home/demo/acme-web",
    name: "Refactor onboarding flow",
    model: "claude-opus-4-6",
    ageMins: 1440,
    favorite: true,
    lines: (cwd, sid) => [
      userMsg(cwd, sid, "Split the 600-line Onboarding component into steps.", mins(1445)),
      asstMsg(cwd, sid, [
        text("Extracted each step into its own component under `src/onboarding/steps/` and left `Onboarding.tsx` as a thin state machine. Down from 612 to 78 lines."),
      ], mins(1442)),
    ],
  },
];

// ── filesystem helpers ────────────────────────────────────────────────────────
const rmrf = (p) => fs.rmSync(p, { recursive: true, force: true });
const mkdirp = (p) => fs.mkdirSync(p, { recursive: true });
const writeJson = (p, obj) => fs.writeFileSync(p, JSON.stringify(obj, null, 2) + "\n");
const writeJsonl = (p, objs) =>
  fs.writeFileSync(p, objs.map((o) => JSON.stringify(o)).join("\n") + "\n");

const linkFromReal = (rel) => {
  const src = path.join(REAL_HOME, rel);
  const dst = path.join(DEMO_HOME, rel);
  if (!fs.existsSync(src)) return false;
  mkdirp(path.dirname(dst));
  try { fs.rmSync(dst, { force: true }); } catch {}
  fs.symlinkSync(src, dst);
  return true;
};
const copyFromReal = (rel) => {
  const src = path.join(REAL_HOME, rel);
  const dst = path.join(DEMO_HOME, rel);
  if (!fs.existsSync(src)) return false;
  mkdirp(path.dirname(dst));
  fs.copyFileSync(src, dst);
  return true;
};

// ── build ─────────────────────────────────────────────────────────────────────
rmrf(DEMO_HOME);
mkdirp(DEMO_HOME);

// Shared auth/config so live turns work (sessions stay isolated below).
copyFromReal(".claude.json");
mkdirp(path.join(DEMO_HOME, ".claude"));
linkFromReal(".claude/.credentials.json");
linkFromReal(".claude/settings.json");
linkFromReal(".claude/CLAUDE.md");

// Pre-approved demo client key → browser connects with no pairing.
mkdirp(path.join(DEMO_HOME, ".config", "xi"));
fs.writeFileSync(
  path.join(DEMO_HOME, ".config", "xi", "clients.edn"),
  `{"${DEMO_CLIENT_KEY}" {:name "Demo Browser" :platform "web" :approved-at 0}}\n`,
);

// Seed sessions + transcripts.
const favorites = [];
for (const s of seeds) {
  const sid = uuid();
  const xiDir = path.join(DEMO_HOME, ".config", "xi", "sessions", encXi(s.cwd));
  const claudeDir = path.join(DEMO_HOME, ".claude", "projects", encClaude(s.cwd));
  mkdirp(xiDir);
  mkdirp(claudeDir);

  writeJson(path.join(xiDir, `${sid}.json`), {
    id: sid,
    "cli-session-id": sid,
    cwd: s.cwd,
    created: iso(mins(s.ageMins)),
    "last-accessed": iso(mins(s.ageMins)),
    name: s.name,
    model: s.model,
    "personal-agent?": false,
  });
  writeJsonl(path.join(claudeDir, `${sid}.jsonl`), s.lines(s.cwd, sid));
  if (s.favorite) favorites.push(sid);
}

// Favorites file (source-agnostic bookmarks keyed by session-id).
if (favorites.length) {
  writeJson(
    path.join(DEMO_HOME, ".config", "xi", "favorites.json"),
    favorites,
  );
}

// Demo user extension (server + web half) — docs/user-extensions.md.
fs.cpSync(
  path.join(REPO, "scripts", "demo-extensions"),
  path.join(DEMO_HOME, ".config", "xi", "extensions"),
  { recursive: true },
);
// Only files the global rules file lists under :extensions are loaded.
fs.writeFileSync(
  path.join(DEMO_HOME, ".config", "xi", "rules.edn"),
  '{:version 1\n :extensions ["notes.cljs"]\n :rules []}\n',
);

// Stash the key so `bb demo` / docs can echo it.
fs.writeFileSync(path.join(DEMO_HOME, ".demo-client-key"), DEMO_CLIENT_KEY + "\n");

console.log(`Seeded demo HOME at ${DEMO_HOME}`);
console.log(`  ${seeds.length} sessions across 2 demo projects`);
console.log(`  user extension: notes (+ web half at /notes)`);
console.log(`  client key: ${DEMO_CLIENT_KEY}`);
