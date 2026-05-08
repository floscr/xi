# Xi

Personal coding agent in ClojureScript + Bun.

## Build

```bash
npm install              # one-time: install shadow-cljs
npx shadow-cljs compile main   # compile CLJS → JS
bun target/main.js       # run
```

## Architecture

- **shadow-cljs** compiles ClojureScript to a single node-script JS file
- **Bun** runs the compiled output (provides HTTP, subprocess, file I/O, fetch)
- **No npm runtime deps** — only shadow-cljs as a devDependency
- Sessions stored in `~/.pi/agent/sessions/` (Pi-compatible JSONL format)
- Auth via `~/.pi/agent/auth.json` OAuth tokens or `ANTHROPIC_API_KEY` env var

## Source layout

```
src/xi/
  cli.cljs             — entry point (creates runtime + connects TUI client)
  runtime.cljs         — headless core (event bus, commands, agent lifecycle)
  runtime/
    events.cljs        — event bus (pub/sub)
    commands.cljs      — command parsing & dispatch
  client/
    tui.cljs           — TUI client (event → component mutations)
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
