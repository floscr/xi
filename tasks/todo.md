# CLI Revision: Simplify Subcommands

## New CLI shape

| Command        | Behavior                                                           |
|----------------|--------------------------------------------------------------------|
| `xi`           | Standalone TUI + runtime (no WS). Can `/join` later from inside.   |
| `xi server`    | Start WS server + create session + attach TUI                      |
| `xi join`      | Connect TUI to latest session on running server                    |
| `xi create`    | Connect TUI to a **new** session on running server                 |
| `xi sessions`  | List sessions on a running server (print & exit)                   |

## Tasks

- [x] 1. Update `cli.cljs` — new `parse-args`, add `:standalone`/`:create`/`:sessions` commands, remove `:auto` and `--new-session`
- [x] 2. Implement `start-standalone!` — create runtime + TUI directly (no WS)
- [x] 3. Implement `list-sessions!` — WS connect, read session list from handshake, print, exit
- [x] 4. Implement `start-create!` — same as `start-join!` but with `session "new"`
- [x] 5. Update `AGENTS.md` to reflect new CLI commands
- [x] 6. Compile — 0 warnings, 0 errors
