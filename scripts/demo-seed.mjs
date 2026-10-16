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

// ── agent blocks (xi.ext.blocks) ─────────────────────────────────────────────
// The blocks live in the session's `.ext.edn` sidecar (xi.ext.persist), as the
// normalized records xi.blocks builds. `edn` writes JS data as EDN: object keys
// become keywords, except inside strMap(); kw() is a keyword value, raw() is
// EDN text as is.
const kw = (s) => ({ __kw: s });
const strMap = (o) => ({ __strMap: o });
const raw = (s) => ({ __raw: s });
const edn = (v) => {
  if (v === null || v === undefined) return "nil";
  if (Array.isArray(v)) return `[${v.map(edn).join(" ")}]`;
  if (typeof v === "string") return JSON.stringify(v);
  if (typeof v !== "object") return String(v);
  if (v.__kw) return `:${v.__kw}`;
  if (v.__raw) return v.__raw;
  if (v.__strMap)
    return `{${Object.entries(v.__strMap).map(([k, x]) => `${JSON.stringify(k)} ${edn(x)}`).join(" ")}}`;
  return `{${Object.entries(v).map(([k, x]) => `:${k} ${edn(x)}`).join(" ")}}`;
};

const blockDemos = [
  { kind: "checklist", title: "Release 2.4", state: { checked: strMap({ "2": true }) },
    spec: { items: [
      { id: "1", label: "Bump the version", done: true },
      { id: "2", label: "Update the changelog", done: false },
      { id: "3", label: "Tag and push", done: false, note: "after CI is green" }] } },
  { kind: "table", title: "Slowest endpoints",
    spec: { columns: [
      { key: "path", label: "Path", align: "left" },
      { key: "p95", label: "p95 (ms)", align: "right" },
      { key: "calls", label: "Calls", align: "right" }],
      rows: [["/api/dashboard", "3920", "18204"], ["/api/events", "610", "90233"],
             ["/api/users/:id", "84", "40512"], ["/api/auth/refresh", "41", "122019"]] } },
  { kind: "keyvalue", title: "Deploy",
    spec: { pairs: [{ key: "Environment", value: "staging" }, { key: "Commit", value: "f142361e" },
                    { key: "Duration", value: "2m 14s" }] } },
  { kind: "steps", title: "Migration",
    spec: { items: [
      { id: "1", label: "Back up the database", status: "done" },
      { id: "2", label: "Add the (org_id, created_at) index", status: "running" },
      { id: "3", label: "Backfill events", status: "todo" },
      { id: "4", label: "Drop the old index", status: "failed", note: "still referenced by a view" }] } },
  { kind: "progress", title: "Test run", spec: { value: 142, max: 200, label: "Specs", note: "3 failing so far" } },
  { kind: "callout", title: "Heads up",
    spec: { tone: "warn", body: "`auth.test.ts` stubs `Date.now()` **globally**; restore it in `afterEach`." } },
  { kind: "chart", title: "Query time (ms)",
    spec: { type: "bar", labels: ["Mon", "Tue", "Wed", "Thu", "Fri"],
            series: [{ name: "", values: [3900, 3700, 410, 52, 40] }] } },
  { kind: "chart", title: "Errors vs. deploys",
    spec: { type: "line", labels: ["1", "2", "3", "4", "5", "6"],
            series: [{ name: "Errors", values: [4, 9, 3, 12, 5, 2] }, { name: "Deploys", values: [1, 2, 1, 3, 2, 1] }] } },
  { kind: "choice", title: "Which fix?", state: { selected: [] },
    spec: { options: [
      { id: "1", label: "Composite index", description: "fast, one migration" },
      { id: "2", label: "Materialized view", description: "faster, needs a refresh job" }],
      multi: false, submit: "Send" } },
  { kind: "form", title: "File an issue",
    spec: { fields: [
      { name: "title", type: "text", label: "Title", required: true },
      { name: "area", type: "select", label: "Area", options: [{ id: "1", label: "Web" }, { id: "2", label: "API" }] },
      { name: "details", type: "textarea", label: "Details" },
      { name: "urgent", type: "checkbox", label: "Urgent" }],
      submit: "File issue" } },
  { kind: "cards", title: "Open PRs",
    spec: { cards: [
      { id: "pr-212", title: "#212 Dark mode toggle", meta: "acme-web · 3 files", body: "Adds `ThemeToggle` to settings.",
        actions: [{ id: "merge", label: "Merge", send: true }, { id: "later", label: "Later", send: false }] },
      { id: "pr-215", title: "#215 Events index", meta: "acme-api · 1 file", body: "Composite index for the dashboard query.",
        actions: [{ id: "merge", label: "Merge", send: true }] }] } },
  // A kind from the bundled `deploys` demo extension (resources/extensions).
  { kind: "deploys/board", title: "Deploys", rules: { actions: ["promote"], send: ["rollback"] },
    textForm: "dev  1.5.0-rc.2  healthy\nstaging  1.4.2  healthy\nprod  1.4.1  degraded",
    spec: { envs: [{ name: "dev", version: "1.5.0-rc.2", status: "healthy" },
                   { name: "staging", version: "1.4.2", status: "healthy" },
                   { name: "prod", version: "1.4.1", status: "degraded" }] } },
  { kind: "hiccup", title: "Custom layout",
    spec: { tree: raw('[:div {} [:div {:layout "row" :gap "sm"} [:ui/badge {:variant "success"} "build passing"] [:ui/badge {:variant "warning"} "2 flaky"]] [:p {:emphasis "muted"} "Hiccup from the agent, from an allowlist."] [:ul {} [:li {} "no classes, ids or styles"] [:li {} "controls only record block actions"]] [:div {:layout "row"} [:ui/button {:action "rerun" :variant "primary"} "Re-run flaky"] [:ui/button {:action "ship" :send true} "Ship it"]]]') } },
];

const blocksLines = (cwd, sid) => [
  userMsg(cwd, sid, "Show me every kind of agent block.", mins(30)),
  ...blockDemos.flatMap((b, i) => [
    asstMsg(cwd, sid, [toolUse(`tu_block${i}`, "mcp__xi-tools__ui_block", { kind: b.kind, title: b.title, id: `demo-${i}` })], mins(29 - i)),
    userBlocks(cwd, sid, [toolResult(`tu_block${i}`, `Showed ${b.kind} "${b.title}" as block demo-${i}.`)], mins(29 - i)),
  ]),
  asstMsg(cwd, sid, [text("That's one block of each kind.")], mins(15)),
];

const blocksExt = () =>
  edn({ blocks: { blocks: strMap(Object.fromEntries(blockDemos.map((b, i) => [`demo-${i}`, {
    id: `demo-${i}`, kind: b.kind, title: b.title, spec: b.spec, state: b.state || {},
    source: { type: kw("tool"), name: "ui_block" }, "call-id": `tu_block${i}`, anchor: 1 + 2 * i,
    actions: [], unread: 0, at: i, "updated-at": i,
    ...(b.rules ? { rules: b.rules, "text-form": b.textForm } : {}),
  }]))) } }) + "\n";

/**
 * Each seed: { cwd, name, model, ageMins, lines(cwd, sid) => transcript objs,
 * ext?() => the session's .ext.edn text }
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
  {
    cwd: "/home/demo/acme-web",
    name: "Agent blocks",
    model: "claude-opus-4-6",
    ageMins: 15,
    lines: blocksLines,
    ext: blocksExt,
  },
  {
    // A long chat for profiling the web timeline (render cost grows with length).
    cwd: "/home/demo/acme-web",
    name: "Long session (perf fixture)",
    model: "claude-opus-4-6",
    ageMins: 2880,
    lines: (cwd, sid) =>
      Array.from({ length: 150 }, (_, i) => {
        const t = mins(2880 + (150 - i) * 2);
        return [
          userMsg(cwd, sid, `Step ${i + 1}: tighten the settings page and re-run the tests.`, t),
          asstMsg(cwd, sid, [
            text(`Reading the page first (pass ${i + 1}).`),
            toolUse(`tu_r${i}`, "Read", { file_path: "src/components/Settings.tsx" }),
          ], t),
          // Distinct text per turn, so render caches see as many entries as a real chat.
          userBlocks(cwd, sid, [toolResult(`tu_r${i}`, `${readResult}\n// pass ${i + 1}`)], t),
          asstMsg(cwd, sid, [toolUse(`tu_e${i}`, "Edit", { file_path: "src/components/Settings.tsx" })], t),
          userBlocks(cwd, sid, [toolResult(`tu_e${i}`, `${editResult}\n// pass ${i + 1}`)], t),
          asstMsg(cwd, sid, [toolUse(`tu_t${i}`, "Bash", { command: `npm test -- ThemeToggle # ${i + 1}` })], t),
          userBlocks(cwd, sid, [toolResult(`tu_t${i}`, `${testResult}\npass ${i + 1}`)], t),
          asstMsg(cwd, sid, [text(`${darkModeFinal}\n\nPass **${i + 1}** done.`)], t),
        ];
      }).flat(),
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
  if (s.ext) fs.writeFileSync(path.join(xiDir, `${sid}.ext.edn`), s.ext());
  if (s.favorite) favorites.push(sid);
}

// Favorites file (source-agnostic bookmarks keyed by session-id).
if (favorites.length) {
  writeJson(
    path.join(DEMO_HOME, ".config", "xi", "favorites.json"),
    favorites,
  );
}

// The bundled demo extensions (resources/extensions) load straight from the
// repo through :demo-extensions below; `notes` has a web half.
const demoExtensions = ["notes.cljs", "hn.cljs", "ping.cljs", "deploys.cljs"];
// Extra user extensions to test against the demo: XI_DEMO_EXTENSIONS_DIR is
// copied into the demo extensions dir (a file or a directory tree), and
// XI_DEMO_EXTENSIONS (comma-separated file names) joins the enabled list.
const extraDir = process.env.XI_DEMO_EXTENSIONS_DIR;
if (extraDir) {
  fs.cpSync(extraDir, path.join(DEMO_HOME, ".config", "xi", "extensions"), {
    recursive: true,
  });
}
const extensions = (process.env.XI_DEMO_EXTENSIONS || "")
  .split(",")
  .map((s) => s.trim())
  .filter(Boolean);
// Only files the user config lists under :extensions / :demo-extensions are
// loaded. Two demo users, so the sidebar's user switcher shows up and multi-user UI (presence,
// avatars, user extensions that talk between users) can be exercised.
fs.writeFileSync(
  path.join(DEMO_HOME, ".config", "xi", "config.edn"),
  '{:type :xi/config\n :version 1\n' +
    ` :demo-extensions [${demoExtensions.map((e) => JSON.stringify(e)).join(" ")}]\n` +
    ` :extensions [${extensions.map((e) => JSON.stringify(e)).join(" ")}]\n` +
    ' :users {"root"  {:name "Demo"}\n' +
    '         "alice" {:name "Alice"}}}\n',
);
fs.writeFileSync(
  path.join(DEMO_HOME, ".config", "xi", "rules.edn"),
  '{:type :xi/rules\n :version 1\n :rules []}\n',
);

// Stash the key so `bb demo` / docs can echo it.
fs.writeFileSync(path.join(DEMO_HOME, ".demo-client-key"), DEMO_CLIENT_KEY + "\n");

console.log(`Seeded demo HOME at ${DEMO_HOME}`);
console.log(`  ${seeds.length} sessions across 2 demo projects`);
console.log(`  demo extensions: ${demoExtensions.join(", ")} (notes has a web half at /notes)`);
if (extensions.length) console.log(`  user extensions: ${extensions.join(", ")}`);
console.log(`  users: root (Demo), alice`);
console.log(`  client key: ${DEMO_CLIENT_KEY}`);
