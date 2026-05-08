# Headless Architecture Refactor

## Plan
- [x] Extract event bus → `src/xi/runtime/events.cljs`
- [x] Extract command dispatch → `src/xi/runtime/commands.cljs`
- [x] Create runtime core → `src/xi/runtime.cljs`
- [x] Create TUI client → `src/xi/client/tui.cljs`
- [x] Slim down `cli.cljs` to 5-line entry point
- [x] Compile with zero new warnings
- [x] Verify identical startup behavior (same crash under `timeout` as before)
- [x] Write architecture doc → `docs/headless-architecture.md`
- [x] Update AGENTS.md source layout

## Review

Refactored `cli.cljs` from a 300-line monolith into:

| File | Lines | Role |
|------|-------|------|
| `runtime/events.cljs` | ~35 | Pub/sub event bus |
| `runtime/commands.cljs` | ~95 | Command parsing, slash command dispatch (returns data, no UI) |
| `runtime.cljs` | ~170 | Headless core: extensions, agent lifecycle, session mgmt |
| `client/tui.cljs` | ~300 | TUI client: subscribes to events, renders components |
| `cli.cljs` | ~8 | Entry point: create runtime + connect TUI client |

The agent core (`loop.cljs`, `provider.cljs`, `tools/`, `ext/`) was untouched — already decoupled.

### What this enables
- Run Xi headless (no terminal needed)
- Connect any client: TUI, HTTP/WS, pipe, tests
- Multiple simultaneous clients possible
- Clean separation: runtime emits events, clients render them
