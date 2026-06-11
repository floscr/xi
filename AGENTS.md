# Xi

Personal coding agent in ClojureScript + Bun.

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

### SDK Version Constraint

The `@anthropic-ai/claude-agent-sdk` must be pinned to **`0.2.110`** — the same version used by the Pi claude-bridge extension. Newer SDK versions (e.g. 0.2.140) produce exit code 127 at runtime because of incompatible Claude CLI resolution. Do not upgrade the SDK without first verifying it works with the installed Claude CLI and bridge.

### Provider Notes

- The SDK query must be explicitly closed after completion via `.close()` to avoid EPIPE errors from orphaned subprocess pipes
- Abort uses `.interrupt()` (graceful) then `.close()` (cleanup), not `.return()`
- Error paths must also close the query before resolving the promise

## Testing

- **Do NOT run `xi` / `bun target/main.js` from the agent.** It's a TUI app that requires an interactive terminal and will not work inside the agent shell. Only compile; the user tests manually.
- The **web client** CAN be agent-tested: run `bun target/main.js server --headless &` and drive a browser via the chrome-devtools tools at `http://localhost:7474`.

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
  EDN strings (`xi.wire`).
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
- `xi join [url]` — connect TUI client to the latest room on a running server
- `xi create [url]` — connect TUI client to a new room on a running server
- `--port N` — override default port (7474)
- The web client is served by the same server at `http://localhost:7474`
- Server hosts multiple rooms; rooms auto-destroy when their last client
  leaves while idle (or a turn ends with no clients attached)
- "Sessions" refers to saved-to-disk conversation history, loaded via `/resume`

## Source layout

```
src/xi/
  cli.cljs             — entry point + assembly (subcommands: server, join, create)
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
    *.cljs             — extensions: kb, web, perplexity, commit, clj_surgeon, gtd,
                         permission_gate, todo_intercept, plan_mode, done_notify,
                         pushover, dictation, terminal_title, clipboard_image,
                         projects, skills, events
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

## Extensions

See [docs/extensions.md](docs/extensions.md) for full details.

- An extension is a **plain data map** — `:id :init :handlers :fx
  :event-hooks :tool-gate :tool-definitions :tool-registry :commands
  :system-prompt :keybindings :prompt-badge :on-shutdown`. No registration
  atoms; extensions are composed at assembly time in `xi.cli` via
  `ext/compose`.
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
