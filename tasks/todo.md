# Subcommand routing for command registry

## Design

Commands can declare `:subcommands` — named sub-handlers that:
1. Appear as separate entries in the `/` palette (e.g. `/diff git`, `/diff staged`)
2. Route automatically: dispatch splits args, matches first word against subcommands
3. Fall through to the main `:handler` when no subcommand matches (default route)

## Tasks

- [x] `command_registry.cljs` — add subcommand support
  - [x] Add `resolve-handler` fn: given cmd + args, return [handler remaining-args]
  - [x] Add `expand-subcommands` + modify `list-commands` to expand them
- [x] `command_palette.cljs` — no changes needed (already uses list-commands)
- [x] `tui.cljs` — update dispatch + refactor diff
  - [x] Update `handle-local-command!` to use `resolve-handler`
  - [x] Refactor `/diff` to use `:subcommands` (git, staged, unstaged)
- [x] Verify: compiles clean, 203 tests pass, 0 warnings
