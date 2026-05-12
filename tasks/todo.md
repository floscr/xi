# Server/Client: Sessions → Rooms

## Naming
- Disk sessions = "sessions" (loaded via `/resume`)
- Server live instances = "rooms" (via `/join`, `/create`)
- Removed runtime `/sessions` command
- No auto-close — rooms persist for server lifetime

## Tasks
- [x] Create `room_manager.cljs`, delete `session_manager.cljs`
- [x] Update `ws.cljs` — imports, protocol events (`:room-joined`, `:rooms` payload)
- [x] Update `ws_transport.cljs` — new event names, `:room` in join msg
- [x] Update `tui.cljs` — `/join` + `/create` commands, handle `:room-joined`
- [x] Update `cli.cljs` — imports, docstring, `xi rooms` subcommand
- [x] Update `runtime/commands.cljs` — remove `/sessions` + `/ls`
- [x] Update `web/ws.cljs` — event names, `join-room!`/`leave-room!`
- [x] Update `web/state.cljs` — `:rooms`, `:room-id`
- [x] Update `web/views.cljs` — labels, function refs
- [x] Compile main + web — 0 warnings
- [x] Tests — 114 tests, 0 failures
- [x] Update AGENTS.md
