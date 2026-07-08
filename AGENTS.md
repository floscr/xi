# Xi

Personal coding agent in ClojureScript + Bun.

> **STOP — before you start, stop, restart, or debug ANY process (server,
> shadow-cljs watch, compile), run `bb check` FIRST.** It prints what's running,
> where (which tmux session + port), and exactly which `bb` task to use. **Never
> start services by hand** (`bun target/main.js ...`, `npx shadow-cljs ...`) and
> never `kill` them by PID — that orphans processes outside the tmux sessions
> and has caused stuck servers before. Always use the `bb` tasks below.

## Build

**Always use `bb` tasks for building — never call `npx shadow-cljs` directly.**

```bash
npm install    # one-time: install shadow-cljs + SDK
bb build       # compile CLJS → JS (node TUI/server)
bb web:build   # compile the browser web client
bb test        # compile and run tests
bb tasks       # list all available tasks
```

Do NOT use `npx shadow-cljs compile ...` — it frequently times out in agent shells. The `bb` tasks handle everything correctly.

### ALWAYS Run `bb check` First

**Before starting, stopping, restarting, or debugging ANY process** (compiles,
servers, watches, or chasing a "stuck"/crashed server), run `bb check` first. It
is the single source of truth for what's already running and saves you from
spawning duplicates or killing the wrong thing:

```bash
bb check
```

It reports: listening ports (7474 main · 7475 personal · 8100 dev-http · 9630
shadow), headless bun server processes, shadow-cljs watch processes, and the
status + recent pane logs of all three tmux sessions — `xi` (`bb dev`,
standalone watch / compile targets), `xi-serve` (`bb serve`), and `xi-pa`
(`bb serve:personal`). It then prints a **guidance block** mapping what runs
where to the exact `bb` task that manages it (start/restart/stop/logs), plus the
rules for when to restart vs. refresh. Read its output before deciding to
start/stop anything.

### Dev Server (shadow-cljs watch) is Usually Already Running

The user typically has a **shadow-cljs watch** process running via `bb dev` or `bb serve` (tmux sessions). This watch process **auto-compiles both the `main` and `web` targets on every file save** — you do NOT need to run `bb build` or `bb web:build` manually.

**Before compiling, run `bb check`** (see above) to see if a watch is already
running.

- If shadow-cljs watch IS running: **do not run `bb build` or `bb web:build`**. Your code changes are compiled automatically within seconds of saving.
- If shadow-cljs watch is NOT running: use `bb build` / `bb web:build` as needed, or start a watch with `bb dev` (standalone) / `bb serve` (with server).
- Running `bb build` while watch is active is harmless but wasteful; running `bb web:build` may conflict with the watch process.
- Never `kill` watch/server processes by PID to clean up — use the matching tmux tasks (`bb dev:stop` / `bb serve:stop`) so sessions stay consistent.

### SDK Version Constraint

The `@anthropic-ai/claude-agent-sdk` must be pinned to **`0.2.110`** — the same version used by the Pi claude-bridge extension. Newer SDK versions (e.g. 0.2.140) produce exit code 127 at runtime because of incompatible Claude CLI resolution. Do not upgrade the SDK without first verifying it works with the installed Claude CLI and bridge.

### Provider Notes

- The SDK query must be explicitly closed after completion via `.close()` to avoid EPIPE errors from orphaned subprocess pipes
- Abort uses `.interrupt()` (graceful) then `.close()` (cleanup), not `.return()`
- Error paths must also close the query before resolving the promise

## Testing

- **Do NOT run `xi` / `bun target/main.js` from the agent.** It's a TUI app that requires an interactive terminal and will not work inside the agent shell. Only compile; the user tests manually.
- The **web client** CAN be agent-tested via the chrome-devtools tools at `http://localhost:7474`.

### IMPORTANT: Always Manage the Server via the `bb serve` Tasks

The server runs in the `xi-serve` tmux session (watch + headless server). **Never launch the server by hand** with `bun target/main.js server --headless &` — a hand-started bun process is orphaned (not in the tmux session), is invisible to `bb serve:stop`/`bb serve:restart`, and has bitten us before (a stray/hung process kept port 7474 occupied with a blocked event loop, so clients got stuck "connecting to server"). Only the `bb serve` tasks start, stop, or restart the server:

```bash
bb serve          # start the xi-serve tmux session (watch + headless server on 7474)
bb serve:restart  # restart it (use this to pick up server-side code changes)
bb serve:stop     # stop it
```

**Always check what's running before starting a new server** — `bb check` reports
the listening ports (7474/7475), any headless bun server processes, and recent
output from the `xi-serve`/`xi-pa` tmux panes:

```bash
bb check
```

- If port 7474 IS in use: **do not start a new server.** Navigate directly to `http://localhost:7474` and test.
- If port 7474 is NOT in use: start it with **`bb serve`** (never raw `bun …`).
- The server is a long-lived bun process and does **not** hot-reload server-side code, even when shadow-cljs watch is running. The browser web client *does* hot-reload — just refresh the page. But for changes to server-side namespaces (`xi.server.*`, `xi.core.*`, `xi.agent`, extensions, providers, etc.) the server must be restarted with **`bb serve:restart`**.
- **Never kill an existing server process** to restart it, and never `kill` a stray bun server by PID — use `bb serve:restart` / `bb serve:stop` so the tmux session stays consistent. If a restart seems risky, ask the user first.

### Unit Tests

```bash
bb test          # compile + run tests once
bb test:watch    # recompile + rerun on file changes
```

Tests use `cljs.test` via the shadow-cljs `:node-test` target. Test files live in `test/` mirroring the `src/` layout (e.g. `test/xi/commands_test.cljs` tests `src/xi/commands.cljs`).

When adding new tests:
1. Create `test/xi/<namespace>_test.cljs` with `(:require [cljs.test :refer [deftest is testing]])`
2. shadow-cljs auto-discovers all `*_test.cljs` namespaces — no registration needed
3. Prefer testing public pure functions; avoid tests that require filesystem or network I/O

## Architecture

See [docs/architecture.md](docs/architecture.md) for the full picture. The short version:

- **One state atom per process.** All logic is pure handlers
  `(fn [state event]) → {:state :effects} | nil`; side effects run only in
  the effect interpreter (`xi.core.app/create-app`).
- **Everything is an event** — prompts, agent output, commands, dialogs,
  room switches, renders. The WS wire protocol is the same event maps as
  transit strings (`xi.wire`).
- **Standalone = not connected.** Server, client, and standalone modes share
  the same state shape and code paths; transports just forward events.
- **Providers are pluggable** (`xi.provider.claude`, `xi.provider.ollama`).
- **shadow-cljs** compiles to a single node script run by **Bun**; the web
  client is a separate `:browser` build served by the same Bun server.
- Runtime npm deps: only `@anthropic-ai/claude-agent-sdk` (pinned, see above).
- Sessions stored in `~/.pi/agent/sessions/` (Pi-compatible JSONL format)
- Personal agent sessions stored separately in `~/.config/xi/personal-agent/root/`
- Auth via `~/.pi/agent/auth.json` OAuth tokens or `ANTHROPIC_API_KEY` env var

### Server / Client

- `xi` — standalone TUI (one local room, connected to nothing)
- `xi server` — WS server + local TUI client in the same process
- `xi server --headless` — headless server (no TUI, clients attach remotely)
- `xi server --personal-agent-only` — personal assistant mode (no coding tools, web_search only)
- `xi prompt <text>` (aka `xi -p`) — one-shot headless run: send a single prompt, print the assistant's response, and exit. Reads stdin when no text is given; `--stream` streams tokens live. No TUI, no server — safe to run from the agent.
- `xi join [url]` — connect TUI client to the latest room on a running server
- `xi create [url]` — connect TUI client to a new room on a running server
- `--port N` — override default port (7474)
- The web client is served by the same server at `http://localhost:7474`
- Server hosts multiple rooms; rooms auto-destroy when their last client
  leaves while idle (or a turn ends with no clients attached)
- "Sessions" refers to saved-to-disk conversation history, loaded via `/resume`
- Clients authenticate with a client key before the server processes their
  events; unknown clients get a 4-digit pairing code, approved via the web
  banner or `bb serve:approve <code>` (`bb serve:pending|clients|revoke`).
  See [docs/client-auth.md](docs/client-auth.md).
- The server can also serve **HTTPS/`wss://`** on a second port (default 7443,
  `XI_TLS_PORT`) when `~/.config/xi/tls/xi.{crt,key}` exist — needed so an iOS
  home-screen PWA keeps its `localStorage` client key (insecure origins get
  their storage evicted). Port 7474 stays plain for the TUI. Setup + per-device
  cert trust (iOS especially): [docs/tls-https.md](docs/tls-https.md).

## Source layout

```
src/xi/
  cli.cljs             — entry point + assembly (subcommands: server, join, create)
  auth.cljs            — client-key auth store (approved/pending clients, pairing codes)
  core/
    state.cljs         — state schema + constructors
    events.cljs        — pure core event handlers (reducer)
    app.cljs           — create-app: dispatch queue, effect interpreter, taps, render scheduling
    log.cljs           — in-memory event ring buffer (elision, delta coalescing)
    jsonl.cljs         — opt-in --debug-events JSONL writer (node-only)
  agent.cljs           — agent turn lifecycle handlers + provider effects
  commands.cljs        — slash commands as pure handlers; input parsing
  compaction.cljs      — /compact (pure handlers + summary-turn effect)
  fx.cljs              — effect handlers (sessions, image processing, model list)
  wire.cljs            — EDN wire protocol (the events ARE the protocol)
  provider/
    claude.cljs        — Claude Agent SDK provider (MCP tool bridge, streaming)
    ollama.cljs        — Ollama provider
  server/
    ws.cljs            — Bun WS server + static serving for the web client
    room_manager.cljs  — rooms as pure event handlers (join/attach/auto-destroy)
  client/
    tui.cljs           — TUI renderer + input layer (render-from-state)
    view.cljs          — history entry → TUI block builders
    ws_transport.cljs  — WS client transport (forward + mirror, reconnect)
  session.cljs         — session persistence (Xi, Claude CLI, Pi formats)
  session/
    format.cljc        — shared session data shapes (cljc)
    sync.cljc          — sync manifest for rsync (cljc)
    tree.cljs          — append-only session tree (currently unwired, see docs/session-tree.md)
    tree_recorder.cljs — event → tree entry mapping (currently unwired)
  system_prompt.cljs   — system prompt construction (base + personal-agent)
  tools/*.cljs         — built-in tools (bash, read, write, edit, grep, find, ls) + registry
  ext/
    core.cljs          — extension composition API (compose, dialogs, tool-gate chain)
    *.cljs             — extensions: kb, web, perplexity, github_code_search,
                         commit, clj_surgeon, gtd, permission_gate, todo_intercept,
                         plan_mode, done_notify, pushover, dictation,
                         terminal_title, clipboard_image, projects, skills, events
  highlight/           — syntax highlighting (engine, grammars, ANSI + CSS themes)
  markdown/            — markdown parsing + ANSI / hiccup rendering
  tui/                 — terminal UI primitives (editor, grid, render, components, …)
  web/
    core.cljs          — browser entry (app assembly, taps, Replicant render)
    views.cljs         — pure views (state → hiccup)
    router.cljs        — routing as events (History API)
    cache.cljs         — localStorage offline cache
  image.cljs           — image resize/processing
  util.cljs            — shared pure utilities
```

### Web Client

Browser-based client built with shadow-cljs `:browser` target and [Replicant](https://github.com/cjohansen/replicant) for rendering. Runs the same pure handlers as the TUI over the WS transport; supports offline mode with localStorage caching.

See [docs/web-client.md](docs/web-client.md) for full documentation (features, protocol, architecture).
See [docs/web-offline.md](docs/web-offline.md) for offline architecture details.
See [docs/frontend.md](docs/frontend.md) for UI component library (clj-ui-framework) usage, theming, and update workflow.

**Always use clj-ui-framework components for web UI — never hand-roll raw HTML
elements (`[:select]`, `[:input]`, `[:button]`, etc.) when a component exists.**
The components carry the project's styling, theming, and accessibility, so a raw
element looks off and drifts from the design system.

- Components live in the `ui.*` namespaces (e.g. `ui.form`, `ui.button`,
  `ui.icon`, `ui.lightbox`, `ui.sidebar`, `ui.theme-toggle`), provided by the
  `clj-ui-framework` git dep (see `deps.edn`).
- `ui.form` covers form controls: `form-input`, `form-textarea`,
  `form-textarea-auto`, `form-select`, `form-checkbox`, `form-radio-group`,
  `form-range`, `form-file`, `form-field`, `form-group`.
- The full component table, props, theming tokens, and the dep-update workflow
  are in [docs/frontend.md](docs/frontend.md). Check it before building any new
  web view, and read the component's source under the gitlibs cache
  (`~/.cache/gitlibs/libs/.../clj-ui-framework/.../src/ui/`) when you need its
  exact prop shape.

## Extensions

See [docs/extensions.md](docs/extensions.md) for full details.
When writing a new extension, follow [docs/writing-extensions.md](docs/writing-extensions.md).

- An extension is a **plain data map** — `:id :init :handlers :fx
  :event-hooks :tool-gate :tool-definitions :tool-registry :commands
  :system-prompt :keybindings :prompt-badge :on-shutdown`. No registration
  atoms; extensions are composed at assembly time in `xi.cli` via
  `ext/compose`.
- **Which extensions load is declared in `src/xi/config.cljc`** — one
  `server` / `client` / `web` vector per surface, shared by all builds via
  custom reader features (`#?(:node …)` for the node builds,
  `#?(:browser …)` for the web build; set in shadow-cljs.edn
  `:compiler-options {:reader-features …}`). Entries are extension maps or
  factory fns `(fn [ctx] → ext|nil)`; `ext/instantiate` supplies the
  per-surface ctx (`server` gets `{:ring … :ask! …}`). Order matters
  (compose chains in order).
- Extension state lives in app state: room-scoped under
  `[:rooms room-id :ext <id>]` (mirrors to clients via `:room/joined`
  snapshots) or process-local under `[:ext <id>]` (never crosses the wire).
- `:tool-gate` is an async transform chain `(fn [tool-call ctx])` →
  tool-call (allow/modify), `nil` (block), or `{:intercepted true :result …}`
  (short-circuit with a result).
- Dialogs: `ext/create-dialogs` returns `ask!` — pushes into room
  `[:ui :dialogs]`, rendered by the TUI/web, resolves a promise on response.

## Conventions

- Use `(aget js/process.env "KEY")` to access env vars (not property access)
- All async code uses JS promises via `(.then p f)` chains
- Tool results are `{:content [{:type "text" :text "..."}] :is-error false}`
