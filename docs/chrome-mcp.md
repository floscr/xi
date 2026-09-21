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
| `XI_CHROME_MCP_TIMEOUT_MS` | Per-call cap (ms) on a chrome-devtools-mcp JSON-RPC request, so a wedged child / stalled stdio pipe surfaces as an error tool-result instead of hanging the turn forever. Default `120000` (generous, so it only fires on a true wedge, never on a legit-slow op); `<= 0` disables. |
| `XI_CHROME_LAUNCH_BIN` | Command used to **start the shared OS Chrome** when it isn't running (attach mode only). May be a full command line — the value is whitespace-split into binary + args (e.g. `google-chrome-stable --remote-debugging-port=9333 --user-data-dir=… about:blank`). Default: the dotfiles `browser` bin. The binary must be absolute or on the *server's* PATH (which doesn't include dotfiles/bin). |
| `XI_CHROME_NO_SCOPE` | Disable workspace scoping even in attach mode (any non-empty value). |
| `XI_CHROME_WORKSPACE` | A dedicated xmonad workspace **name** used as the last-resort anchor when no driving terminal resolves — e.g. **web-client** or server-started sessions that have no local terminal PID. **Defaults to `mcp`** when unset, so such turns act on (and create their windows on) a dedicated `mcp` workspace instead of being refused; set this to point them at a different workspace name. Real TUI sessions still resolve their own workspace per client PID, so this only applies when nothing else does. |
| `XI_CHROME_OWN_WINDOWS_ONLY` | Isolate by **window ownership** instead of xmonad workspace (any non-empty value). Each agent only ever acts on Chrome windows *it* created — for several agents sharing one Chrome on one workspace (see [Owned-windows-only mode](#owned-windows-only-mode)). |
| `XI_CHROME_WM_CLASS` | WM_CLASS substring identifying the shared Chrome for scoping (default `chrome-profile-stable`). |
| `XI_CHROME_WM_BIN` | Absolute path to the dotfiles `wm` CLI used for scoping (default `/home/floscr/.config/dotfiles/bin/wm`). Must be absolute — the server's PATH doesn't include dotfiles/bin. |
| `XI_CHROME_WMCTRL_BIN` | Absolute path to `wmctrl`, used to map a client PID → X11 window for per-session workspace resolution (default `/etc/profiles/per-user/floscr/bin/wmctrl`). |

Chrome is launched **lazily on the first tool call** and killed on shutdown.
The connect promise is memoized, and cleared on failure so a later call
retries.

### Auto-launching the OS Chrome (attach mode)

In attach mode (`XI_CHROME_BROWSER_URL`) xi drives an *external* Chrome over its
remote-debugging URL. If that Chrome process isn't running, every CDP /
chrome-devtools-mcp call fails with a connection error. So before forwarding any
tool call, xi probes the CDP browser endpoint (`<browser-url>/json/version`) and
— when it's unreachable — **spawns Chrome itself** (the `XI_CHROME_LAUNCH_BIN`
launcher, detached) and waits (~10s cap) for the endpoint to come up. This lets
an agent bootstrap the browser instead of requiring a human to start it first.
Concurrent calls share a single in-flight launch, so Chrome is only started
once. In non-attach mode this doesn't apply — `chrome-devtools-mcp` launches its
own managed Chrome. See `xi.ext.chrome-mcp.launch`.

**Placed on the agent's workspace.** A cold-launched Chrome maps its first
window on whatever workspace the user is *currently viewing*, not the agent's.
So when scoping is active and the launch happens, the guard waits for that
window to map, then **moves every fresh Chrome window onto the agent's
workspace** and adopts them as owned (a cold launch means no other Chrome
windows exist yet, so all of them belong to this launch). The bootstrap window
therefore lands where the agent works, not on the viewed workspace.

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

## Owned-windows-only mode

Workspace scoping assumes each agent is on its own xmonad workspace. When
**several agents share one Chrome on one workspace** — e.g. running
`hn-hiring apply` for several jobs in parallel, each a standalone `xi --prompt`
process with no driving TUI terminal — they all anchor to the *same* workspace
(the one the shared Chrome's windows live on) and see the same tabs, so
workspace scoping can't tell them apart. One agent could then navigate or close another agent's tab.

Set `XI_CHROME_OWN_WINDOWS_ONLY=1` to switch the isolation axis from *workspace*
to *ownership*: an agent only ever acts on the Chrome windows it created itself.
Each `bb apply` is its own process with its own guard state (`owned*`), so
ownership is naturally per-agent.

- The membership set is the windows this process opened (tracked by CDP window
  id), not the ones on some workspace. `list_pages` is filtered to them;
  `select_page`/`close_page` are blocked for anyone else's tab; `new_page` opens
  a tab in this agent's own window; and the reconcile step (before every
  page-acting tool) selects an owned page or, if none exists yet, **creates one
  first** — so the agent never inherits chrome-devtools-mcp's default selection
  (which could be another agent's tab).
- No `wm` calls are made in this mode — isolation rides purely on CDP window
  ids — and the "wm blind → passthrough" fallback is disabled (passing through
  raw would drop isolation). Windows are **not** relocated to any workspace;
  they land wherever Chrome opens them.
- Focus theft is a separate concern: the shared Chrome must still be launched
  with a `WM_CLASS` the desktop's activation-ignore rule recognizes (on this
  setup, an instance name containing `chrome-profile-stable`, e.g. Chrome's
  `--class=chrome-profile-stable`). Otherwise every navigation raises the window
  and steals focus regardless of scoping.

Implementation: `xi.ext.chrome-mcp.guard` keys membership on a per-process
sentinel workspace name — owned windows are recorded under it and it's used as
the `launch-workspace`, so `scope/classify`'s existing owned-window merge yields
exactly the owned set with no new classification path.

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

**Sub-agent turns carry the parent room's client PID** (`xi.subagent` threads
`agent/room-client-pid` into the turn's tool ctx), so a background sub-agent's
browser calls scope to the same terminal workspace as the parent session.

When no `:client-pid` is present (a **web client**, which has no local terminal
window, or any caller that didn't send a PID) — or the PID can't be placed —
the guard falls back, in order, to:

1. the **last PID-resolved workspace** of this server process (cached), so
   web-client / sub-agent turns in a session that *also* has a driving terminal
   follow that terminal's workspace, then
2. a **dedicated workspace** name — `XI_CHROME_WORKSPACE` if set, else the
   default **`mcp`** workspace.

The dedicated workspace is the anchor for **headless / web-only** sessions:
they have no local terminal PID, so step 1 never resolves and the guard always
lands on the `mcp` workspace (moving the agent's Chrome windows there), never on
wherever a stray Chrome window happens to sit. Set `XI_CHROME_WORKSPACE` to
reserve a different name. It is a *fixed* designated workspace, never `wm
current`, so it does not chase the user's gaze.

Note the fallback **deliberately ignores existing Chrome windows on other
workspaces** — a headless/web session must always act on its own fixed
workspace, not adopt a window the user (or a prior terminal turn) left
elsewhere.

It **never** falls back to `wm current`: the viewed workspace follows the
user's eyes, not the agent — anchoring to it made every action taken while the
user viewed another workspace self-heal an `about:blank` window *on that
workspace* (windows chasing the user's gaze). Because the fallback always
resolves a workspace, a call is never refused for lack of one; if scoping errors
out it fails closed — **blocked**, never forwarded unscoped (unscoped acts on
Chrome's focused window, i.e. wherever the user is), and never aimed at the
viewed workspace.

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
  window's `:workspace` name;
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
instant before `wm move` relocates it — eliminating that *last* flash entirely
is a WM-config concern (an xmonad `ManageHook`/`doShift`), not something CDP can
do. Note this only applies to the **first** window on a workspace: once one
exists, `new_page` reuses it as a tab (see the `new_page` gate below), so
repeated opens never create a window and never flash.

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
| `new_page` | **Reuses a window on the agent's workspace when one exists** — opens the URL as a plain tab in it (CDP `Target.createTarget` with an explicit `windowId`, `newWindow:false`), so no new OS window is mapped and there is **no flash**. Only when the workspace has *no* Chrome window does it spawn a fresh window and **move it onto the workspace** (Chrome opens new windows on the *currently viewed* workspace — this pins it, and is the one path that can still flash on a tiling WM). |

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
