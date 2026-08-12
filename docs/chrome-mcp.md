# Chrome DevTools MCP (`xi.ext.chrome-mcp`)

Proxies [`chrome-devtools-mcp`](https://github.com/ChromeDevTools/chrome-devtools-mcp)
into xi's own tool surface, so an agent running through the Claude bridge can
drive a browser — navigate pages, click, snapshot the a11y tree, run
performance traces, read console/network — as ordinary xi tools.

## Why this exists

xi's Claude provider **disables the Claude CLI's native MCP servers**: only
xi's in-process MCP is exposed to a run (see `xi.provider.claude/query-opts`).
So a `chrome-devtools-mcp` you declare in `~/.claude.json` never reaches an
agent turn started *inside* xi.

This extension re-provides it. xi spawns `chrome-devtools-mcp` as a child MCP
server over stdio and forwards each call. The payoff: every browser call flows
through xi's **tool-gate**, so it's governable and redirectable exactly like a
built-in tool. In attach mode this is used to **scope the agent to the xmonad
workspace of the TUI driving the session** (see
[Workspace scoping](#workspace-scoping) below).

```
model → xi in-process MCP (mcp__xi-tools__navigate_page …)
      → tool-gate → chrome registry fn
      → hand-rolled stdio JSON-RPC client → chrome-devtools-mcp → Chrome
```

The stdio client is hand-rolled (~90 lines, newline-delimited JSON-RPC 2.0) so
xi keeps its single runtime dependency rule — no `@modelcontextprotocol/sdk` is
added (see AGENTS.md).

## Enabling it

Opt-in via env — the factory returns `nil` unless `XI_CHROME_TOOLS` is set, so
the 29 browser tools aren't advertised on every turn by default.

| Env var | Effect |
|---------|--------|
| `XI_CHROME_TOOLS` | Enable the extension (any non-empty value). |
| `XI_CHROME_BROWSER_URL` | Attach to an existing Chrome's remote-debugging URL (passed as `--browserUrl`) instead of letting chrome-devtools-mcp launch its own. |
| `XI_CHROME_MCP_ARGS` | Extra CLI args for `chrome-devtools-mcp`, space-split. |
| `XI_CHROME_NO_SCOPE` | Disable workspace scoping even in attach mode (any non-empty value). |
| `XI_CHROME_WM_CLASS` | WM_CLASS substring identifying the shared Chrome for scoping (default `chrome-profile-stable`). |
| `XI_CHROME_WM_BIN` | Absolute path to the dotfiles `wm` CLI used for scoping (default `/home/floscr/.config/dotfiles/bin/wm`). Must be absolute — the server's PATH doesn't include dotfiles/bin. |
| `XI_CHROME_WMCTRL_BIN` | Absolute path to `wmctrl`, used to map a client PID → X11 window for per-session workspace resolution (default `/etc/profiles/per-user/floscr/bin/wmctrl`). |

Chrome is launched **lazily on the first tool call** and killed on shutdown.
The connect promise is memoized, and cleared on failure so a later call
retries.

Because it's a server-side extension, set the env before starting the server
and pick it up with `bb serve:restart`:

```bash
XI_CHROME_TOOLS=1 bb serve:restart
```

### NixOS / non-standard Chrome path

`chrome-devtools-mcp` looks for Chrome at `/opt/google/chrome/chrome`. On NixOS
(or anywhere Chrome isn't at the default path) point it at your binary via
`XI_CHROME_MCP_ARGS`:

```bash
XI_CHROME_TOOLS=1 \
XI_CHROME_MCP_ARGS="--executablePath /etc/profiles/per-user/$USER/bin/google-chrome-stable" \
bb serve:restart
```

Or attach to an already-running Chrome started with
`--remote-debugging-port=9222`:

```bash
XI_CHROME_TOOLS=1 XI_CHROME_BROWSER_URL=http://127.0.0.1:9222 bb serve:restart
```

## Workspace scoping

With several agents running at once — each in its own xmonad workspace but
**sharing one Chrome** (one profile, `--remote-debugging-port=9222`) — an
unscoped agent sees and can close *every* tab in every window, including ones
belonging to another workspace/agent. Scoping fixes that: an agent only ever
acts on Chrome windows on **the workspace of the TUI driving its session**, and
creates new windows there.

Active whenever `XI_CHROME_BROWSER_URL` is set (attach mode) and a workspace
resolves; disable with `XI_CHROME_NO_SCOPE=1`.

#### Which workspace: per-session, from the driving TUI's PID

The headless server hosts many concurrent sessions, each driven by a TUI on a
*different* workspace. So the target workspace can't be a global signal — in
particular **not** `wm current`, which returns the workspace the user's eyes
are on (whatever they last switched to), not the one the calling terminal lives
on. That mismatch was the "windows open on the wrong workspace" bug: switch
away to read a page elsewhere and new windows chased your gaze instead of
staying with the agent's terminal.

Instead the workspace is resolved **per session from the driving client's
PID**:

1. A TUI/CLI client sends its OS PID in its `:auth/hello` (`cli.cljs`).
2. The server stores it in the client registry (`ws.cljs`), and `agent.cljs`
   picks the room's client PID (preferring the `tui` platform) into the turn's
   tool ctx as `:client-pid` (via `provider/claude.cljs`).
3. The chrome guard resolves that PID to a workspace name
   (`wm/workspace-for-pid`): walk `/proc/<pid>/stat` ancestry (the bun client
   is a descendant of its terminal emulator) → `wmctrl -lp` maps the terminal
   PID to its X11 window id → `wm windows --all --json` maps that window to its
   workspace **name**.

When no `:client-pid` is present (a **web client**, which has no local terminal
window, or any caller that didn't send a PID) the guard falls back to
`wm current`. So the PID path is **terminal-only**; web sessions get
viewed-workspace behavior.

Workspaces are keyed by **name**, never index: xmonad workspaces grow and
shrink as they are created/destroyed, so a desktop *index* is unstable — it can
point at a different workspace minutes later.

Neither `wm` nor `wmctrl` is on the server process' PATH, so both are invoked by
**absolute path** (`XI_CHROME_WM_BIN`, default
`/home/floscr/.config/dotfiles/bin/wm`; `XI_CHROME_WMCTRL_BIN`, default
`/etc/profiles/per-user/floscr/bin/wmctrl`). A bare `wm` silently fails, which
turns scoping off (passthrough).

### How a tab is placed on a workspace

chrome-devtools-mcp addresses pages by a flat index and never says which
*window* (let alone workspace) a page is in. CDP groups tabs by window, but
knows nothing about xmonad. The bridge is built from three sources:

```
mcp page  --URL exact-match-->  CDP target
          --Browser.getWindowForTarget-->  CDP windowId
          --tab title match-->  X11 window (wm)  -->  workspace name
```

- **mcp ↔ CDP**: exact **URL** match.
- **CDP window ↔ X11 window**: **title** match (X11 exposes only the active
  tab's title, so a page is classified via *any* sibling tab in its CDP
  window). Window **geometry can't** be used — CDP bounds differ from X11 by
  HiDPI scale + origin, and xmonad tiling gives windows on different
  workspaces identical bounds.
- **X11 window → workspace**: the window's xmonad workspace **name**, read by
  the dotfiles `wm` CLI (`wm windows --all --class … --json` exposes each
  window's `:workspace` name; `wm current` is the viewed workspace name;
  `wm move --window W --to <name>` relocates by name). Names are used
  throughout because workspace indices are unstable (workspaces grow/shrink).

- **Self-created windows** are tracked by CDP windowId and always count as the
  agent's, regardless of title. A freshly self-healed `about:blank` window has
  no title to correlate against an X11 window, so title matching can't place it;
  owning it does. Without this the reconcile step would create a *new* blank
  window on every call (it never recognized the one it just made).

**Fail-safe:** if a page's workspace can't be determined, it is treated as
*not* the agent's — the agent never touches what it can't place.

**No focus theft:** self-created windows are opened via CDP
`Target.createTarget` with `background: true`, so the new window is *not*
activated/focused. Chrome opens it on the currently-viewed workspace before the
guard moves it to the agent's workspace; `background` keeps that transient
window from stealing focus (or, on a tiling WM, the keyboard) while it's still
there. On a tiling WM the window may still reflow the viewed layout for the
instant before `wm move` relocates it — eliminating that entirely is a
WM-config concern (an xmonad `ManageHook`/`doShift`), not something CDP can do.

### What the gates do

The invariant is that the mcp **selected page is always a current-workspace
page**, so every page-acting tool (click, navigate, screenshot, …) is scoped
automatically. It's re-established by the reconcile step and preserved by
gating the four page-management tools:

| Tool | Behavior |
|------|----------|
| `list_pages` | **Read-only:** filtered to current-workspace pages (never creates a window — self-heal is the reconcile step's job). |
| `select_page` | **Blocked** when the target page is on another workspace. |
| `close_page` | **Blocked** when the target page is on another workspace. |
| `new_page` | Any brand-new window the call spawns is **moved onto the current workspace** (Chrome opens new windows on the *currently viewed* workspace, which is normally the same one — this pins it). |

Self-heal (creating a current-workspace window when none exists) is the
**reconcile** step that runs before any page-acting tool — not `list_pages`,
so a read never spawns a window.

"Favor, not wall": the agent works within its own workspace's windows and
self-heals when there are none, rather than reaching into another workspace.

## Tool definitions are baked at compile time

xi collects `:tool-definitions` **synchronously at assembly** — there is no
async seam to discover chrome's tool list via `tools/list` per run. So the 29
tool defs are inlined into the bundle at compile time:

- `resources/chrome/tools.edn` — the generated defs (committed).
- `xi.ext.chrome-mcp.defs` — a `.clj` macro that `slurp`s + inlines that EDN
  (mirrors `xi.highlight.bundle`; `resources/` isn't on the classpath, so the
  macro reads the file by repo-relative path at compile time).
- `scripts/sync-chrome-tools.mjs` — regenerates the EDN from
  `chrome-devtools-mcp`'s live `tools/list`, transforming each `inputSchema`
  into xi's expected shape (structural keys as keywords, property-name keys as
  strings, so the JSON-Schema→Zod converter in `xi.provider.claude` handles
  nested `:required` correctly).

Regenerate when bumping `chrome-devtools-mcp`:

```bash
bb chrome:sync-tools   # → rewrites resources/chrome/tools.edn; rebuild to inline
```

## Files

| Path | Role |
|------|------|
| `src/xi/ext/chrome_mcp.cljs` | The extension: stdio JSON-RPC client + forward registry; wraps forward with the scope guard in attach mode. |
| `src/xi/ext/chrome_mcp/guard.cljs` | Workspace-scoping orchestration (the four gates + reconcile/self-heal). |
| `src/xi/ext/chrome_mcp/scope.cljs` | Pure classification (parse `list_pages`, title/URL correlation) + unit tests. |
| `src/xi/ext/chrome_mcp/cdp.cljs` | Minimal CDP WebSocket client (`Target.getTargets`, `getWindowForTarget`, `createTarget`). |
| `src/xi/ext/chrome_mcp/wm.cljs` | Async wrappers around the dotfiles `wm` CLI, by absolute path (current workspace name, window list, move-by-name) + `workspace-for-pid` (PID → X11 window via `/proc` ancestry + `wmctrl -lp` → workspace name). |
| `src/xi/ext/chrome_mcp/defs.clj` | Compile-time macro inlining the tool defs. |
| `resources/chrome/tools.edn` | Generated tool defs (29 tools). |
| `scripts/sync-chrome-tools.mjs` | Regenerator (`bb chrome:sync-tools`). |
