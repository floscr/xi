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
  tools/*.cljs         — built-in tools (read, write, edit, bash, grep, ls)
  ext/                 — extensions (plan-mode, kb, commit, permission-gate, etc.)
  tui/                 — terminal UI rendering primitives
```

## Conventions

- Use `(aget js/process.env "KEY")` to access env vars (not property access)
- All async code uses JS promises via `(.then p f)` chains
- Tool results are `{:content [{:type "text" :text "..."}] :is-error false}`
