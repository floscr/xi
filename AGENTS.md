# Xi

Personal coding agent in ClojureScript + Bun.

## Build

```bash
npm install              # one-time: install shadow-cljs
npx shadow-cljs compile main   # compile CLJS → JS
bun target/main.js       # run
```

## Testing

- **Do NOT run `xi` / `bun target/main.js` from the agent.** It's a TUI app that requires an interactive terminal and will not work inside the agent shell. Only compile; the user tests manually.

### Unit Tests

```bash
npm test                       # compile + run tests once
npm run test:watch             # recompile + rerun on file changes
npx shadow-cljs compile test   # equivalent to npm test
```

Tests use `cljs.test` via the shadow-cljs `:node-test` target. Test files live in `test/` mirroring the `src/` layout (e.g. `test/xi/commands_test.cljs` tests `src/xi/runtime/commands.cljs`).

When adding new tests:
1. Create `test/xi/<namespace>_test.cljs` with `(:require [cljs.test :refer [deftest is testing]])`
2. shadow-cljs auto-discovers all `*_test.cljs` namespaces — no registration needed
3. Prefer testing public pure functions; avoid tests that require filesystem or network I/O

## Architecture

- **shadow-cljs** compiles ClojureScript to a single node-script JS file
- **Bun** runs the compiled output (provides HTTP, subprocess, file I/O, fetch)
- **No npm runtime deps** — only shadow-cljs as a devDependency
- Sessions stored in `~/.pi/agent/sessions/` (Pi-compatible JSONL format)
- Auth via `~/.pi/agent/auth.json` OAuth tokens or `ANTHROPIC_API_KEY` env var

### Server / Client

- `xi` — standalone TUI + runtime (no WS server; can `/join` later)
- `xi server` — start WS server + create session + attach local TUI
- `xi server --headless` — start headless server (no TUI, clients attach remotely)
- `xi join` — connect TUI client to latest session on a running server
- `xi create` — connect TUI client to a new session on a running server
- `xi sessions` — list active sessions on a running server (print & exit)
- `--port N` — override default port (7474)
- Server hosts multiple sessions; each session is an independent runtime
- When the last client disconnects from a session, the session is destroyed

## Source layout

```
src/xi/
  cli.cljs             — entry point (subcommand parsing: server, join, create, sessions)
  runtime.cljs         — headless core (event bus, commands, agent lifecycle)
  runtime/
    events.cljs        — event bus (pub/sub)
    commands.cljs      — command parsing & dispatch
  server/
    ws.cljs            — WS server (session-aware, join handshake)
    session_manager.cljs — manages multiple runtime sessions
  client/
    tui.cljs           — TUI client (event → component mutations)
    ws_transport.cljs  — WS client transport (sends join handshake)
  loop.cljs            — agent loop (wraps provider/SDK)
  provider.cljs        — Claude Agent SDK integration, MCP tool bridge
  session.cljs         — session persistence (Xi, Claude CLI, Pi formats)
  state/
    session.cljs       — accessor fns for hook state (session-title, cwd, model, etc.)
  tools/*.cljs         — built-in tools (read, write, edit, bash, grep, ls)
  ext/
    core.cljs          — extension registry, hook dispatch, hook state management
    done_notify.cljs   — desktop notification on agent finish (Ctrl+Shift+N toggle)
    permission_gate.cljs — blocks writes to sensitive paths
    plan_mode.cljs     — read-only exploration mode (/plan)
    terminal_title.cljs — sets terminal title from session name
    parmezan.cljs      — auto-fix Clojure delimiters after writes
    kb.cljs            — knowledge base tools
    commit.cljs        — git commit workflow tools
    web.cljs           — URL fetch tool
  tui/                 — terminal UI rendering primitives
```

## Extensions

See [docs/extensions.md](docs/extensions.md) for full details.

- All hooks receive a **state map** auto-injected by `ext/core`. Use accessor fns from `xi.state.session`:
  ```clojure
  (state.session/session-title state)  ;; session name or nil
  (state.session/cwd state)            ;; working directory
  (state.session/model state)          ;; active model
  ```
- The runtime calls `sync-hook-state!` at startup and after each turn to keep hook state current
- Transform hooks (`:tool-call`, `:context`) receive `(value, state)` — return `nil` to block
- Prompt badge hooks return a string shown after `xi>` in the prompt

## Conventions

- Use `(aget js/process.env "KEY")` to access env vars (not property access)
- All async code uses JS promises via `(.then p f)` chains
- Tool results are `{:content [{:type "text" :text "..."}] :is-error false}`
