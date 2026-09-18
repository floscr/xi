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
- **To self-test the web UI, prefer the isolated demo server (`bb demo`, port 7476) — NOT the real :7474 server unless the user explicitly asks.** The demo runs with `HOME` redirected at a gitignored `.demo-home/` seeded with fake sessions, so it can never see or mutate the user's real active session. Open `http://localhost:7476`, install the pre-approved demo client key from `bb demo:key` into `localStorage` (`xi-client-key`), reload, and drive it. See [docs/demo.md](docs/demo.md).

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
- **`bb serve:restart` / `bb serve:stop` deliberately sever your own connection.** They kill the `xi-serve` server that hosts your session, so once approved the permission gate runs them **detached** and returns a success result immediately. Your WS link to :7474 dropping right after (a "stream closed"/"connecting to server" blip) is the **expected sign it worked**, not a failure — do NOT retry the command. The server is back within ~5s; confirm with `bb check` or by reloading the page.

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
- **Providers are pluggable** (`xi.provider.claude`, `xi.provider.ollama`,
  `xi.provider.openai.codex`, `xi.provider.zen`). Zen (OpenCode Zen gateway)
  routes `opencode/<id>` models across several API surfaces; chat-completions,
  Anthropic Messages, and OpenAI Responses (GPT/Grok/Muse, incl. GPT 6 Astra)
  are implemented today. See [docs/providers-zen.md](docs/providers-zen.md).
  The OpenAI Codex provider routes `openai/<id>` models (gpt-5.1-codex, …) to
  the ChatGPT-subscription Codex backend, reusing the `codex` CLI's credentials
  from `~/.codex/auth.json`. The shared OpenAI Responses SSE + tool-loop
  machinery lives in `xi.provider.openai.responses`. See
  [docs/providers-openai.md](docs/providers-openai.md).
- **shadow-cljs** compiles to a single node script run by **Bun**; the web
  client is a separate `:browser` build served by the same Bun server.
- Runtime npm deps: only `@anthropic-ai/claude-agent-sdk` (pinned, see above).
- Session metadata stored in `~/.config/xi/sessions/`; conversation transcripts
  live in Claude CLI sessions under `~/.claude/projects/`
- Personal agent sessions stored separately in `~/.config/xi/personal-agent/root/`
- Auth via `~/.pi/agent/auth.json` OAuth tokens or `ANTHROPIC_API_KEY` env var

### Server / Client

- `xi` — standalone TUI (one local room, connected to nothing)
- `xi server` — WS server + local TUI client in the same process
- `xi server --headless` — headless server (no TUI, clients attach remotely)
- `xi server --personal-agent-only` — personal assistant mode (no coding tools, web_search only)
- `xi prompt <text>` (aka `xi -p`) — one-shot headless run: send a single prompt, print the assistant's response, and exit. Reads stdin when no text is given; `--stream` streams tokens live; `--no-store` runs ephemerally (leaves no session behind). No TUI, no server — safe to run from the agent.
- `xi join [url]` — connect TUI client to the latest room on a running server
- `xi create [url]` — connect TUI client to a new room on a running server
- `xi help` (aka `--help`, `-h`) — print CLI usage and exit
- `--port N` — override default port (7474)
- The web client is served by the same server at `http://localhost:7474`
- Full CLI reference (commands, flags, env): [docs/cli.md](docs/cli.md)
- The server exposes a small HTTP API on the same port: `POST /api/rooms`
  creates a room (and optionally starts a turn) so external tooling — e.g. the
  dotfiles GTD service — can spawn a background agent session and get back a
  `/chat/<session-id>` URL. Auth is the same client-key as WS (`Authorization:
  Bearer` / `X-Xi-Client-Key`). See [docs/server.md](docs/server.md#http-api).
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
    openai_compat.cljs — shared OpenAI Chat Completions streaming + tool loop
    ollama.cljs        — Ollama provider (thin wrapper over openai_compat)
    zen.cljs           — OpenCode Zen gateway provider (dispatches by wire format)
    openai/
      responses.cljs   — shared OpenAI Responses SSE + tool-loop machinery
                         (used by both the Zen-responses and Codex adapters)
      auth.cljs        — Codex CLI credential reuse (~/.codex/auth.json + refresh)
      codex.cljs       — OpenAI ChatGPT-subscription (Codex) provider: routes
                         `openai/<id>` to chatgpt.com/backend-api/codex/responses
    zen/
      auth.cljs        — Zen API key resolution (env + OpenCode auth.json)
      models.cljs      — Zen id normalization + wire-format routing table
      anthropic.cljs   — Zen Anthropic Messages surface adapter (raw HTTP)
      responses.cljs   — Zen OpenAI-Responses request shape (over openai/responses)
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
  mcp/
    client.cljs        — JSON-RPC MCP client: stdio + Streamable-HTTP transports
                         (consume external MCP servers)
  ext/
    core.cljs          — extension composition API (compose, dialogs, tool-gate chain)
    manager.cljs       — live extension registry: runtime enable/disable +
                         :on-enable/:on-disable hooks (see docs/mcp-servers.md)
    extensions.cljs    — /ext list|enable|disable control command
    mcp.cljs           — MCP-as-extension helper + /mcp command (external MCP
                         servers → tools, stdio + http; see docs/mcp-servers.md)
    config.cljs        — generic per-extension gitignored secret loader
                         (~/.config/xi/ext/<id>.env; see docs/config.md)
    render.cljs        — Render.com MCP server as a disabled-by-default http
                         extension (API key from config; see docs/mcp-servers.md)
    treesitter/        — tree-sitter outline extension: `read` of large source
                         files → structural outline via a native nix-built CLI
                         (native/xi-treesitter, `bb treesitter:install`), plus a
                         read_source tool for literal code — see docs/treesitter.md
    *.cljs             — extensions: kb, web, perplexity, github_code_search,
                         commit, clj_surgeon, permission_gate,
                         plan_mode, done_notify, pushover, dictation,
                         resume (/trim /rollover /lineage — see docs/resume.md),
                         terminal_title, clipboard_image, projects, skills, events,
                         chrome (chrome-devtools-mcp proxy — see docs/chrome-mcp.md),
                         element_picker (visual DOM element picker → prompt,
                         installed into chrome — see docs/element-picker.md),
                         design_mode (persistent in-browser design mode: Ctrl+I
                         picks elements, each request runs in a background
                         sub-agent, survives navigation — installed into chrome,
                         see docs/design-mode.md),
                         style_editor (agent-driven live style editor: sliders /
                         color pickers on a page element → committed CSS values,
                         installed into chrome — see docs/style-editor.md)
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

Outside `src/`: `bb-client/` — a Babashka/JVM client lib (`xi.client/prompt!`)
for calling xi's one-shot prompt mode from other services, paired with named
agent profiles (`xi prompt --agent`). See [docs/bb-client.md](docs/bb-client.md).

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
For **runtime enable/disable** and **consuming external MCP servers**, see
[docs/mcp-servers.md](docs/mcp-servers.md).

- An extension is a **plain data map** — `:id :init :handlers :fx
  :event-hooks :tool-gate :tool-definitions :tool-registry :commands
  :system-prompt :keybindings :prompt-badge :on-shutdown :on-enable
  :on-disable`. No registration atoms; extensions are composed at assembly
  time in `xi.cli` via `ext/compose`.
- **Runtime enable/disable**: `xi.ext.manager` keeps the composition live so
  extensions can be toggled mid-session (`/ext list|enable|disable`), firing
  `:on-enable` / `:on-disable`. Only *use-time* surfaces (tools + tool-gate,
  which the provider re-reads per turn) hot-swap; handler/command/keybinding/
  system-prompt surfaces need a restart. **External MCP servers** are wrapped
  as extensions on top of this (`xi.ext.mcp`, `/mcp` command,
  `~/.config/xi/mcp.edn`) — no MCP-specific provider code.
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
- **Always document config options.** The full config reference is
  [docs/config.md](docs/config.md) — keep it in sync when adding options.
  User-tunable options live in `src/xi/config.cljc` (e.g. the `tui` overrides
  map). Each option's *default*
  lives in its consuming namespace so the code is usable without a config entry;
  the effective value is read via a loader that falls back to that default when
  the key is absent (`config/tui-opt`). Declare TUI options with the
  `deftui-opt` macro (`xi.config-macros`), which pairs the default with the
  config lookup in one form:
  `(deftui-opt truncate-output-block-after-n-lines 100 "doc…")`. Whenever you
  add an option, document it both at the `deftui-opt` call site and in the
  `tui` map's docstring — its key, what it does, its default, and the owning
  namespace. (The macro lives in its own `.clj`, not `config.cljc`, because a
  `.cljc` whose requires are all behind `:node`/`:browser` reader features
  can't be loaded as a JVM macro namespace.)

<!-- clj-ui-framework:begin -->
## UI Framework — clj-ui-framework

This repo uses the shared **clj-ui-framework** component library
(cross-target Clojure/ClojureScript/Squint UI components, theme
tokens, icons, and browser JS runtime), pinned as a git dependency
in this project's `bb.edn`/`deps.edn`.

- Local checkout: `~/Code/Projects/clj-ui-framework`
- Remote (git dep source): <https://git.example.com/floscr/clj-ui-framework>

Before doing UI work here, read the framework's `AGENTS.md` — it
documents the available components and icons (full generated list in
`docs/components.md`), how to add new components and icons, per-target
pitfalls (hiccup/replicant/squint), theming/tokens, and the JS runtime.
Update the framework by bumping the pinned `:sha` in this project's
`bb.edn`/`deps.edn`.
<!-- clj-ui-framework:end -->
