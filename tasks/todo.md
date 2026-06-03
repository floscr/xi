# GTD Extension Enhancements

## Changes

### org-mode-agenda-cli (2 files)
- [x] `src/gtd/change.clj` — Add `properties` option to `change-task!` (map of key→value, applied via `a.props/set-property`)
- [x] `src/gtd/core.clj` — Add `--property KEY=VALUE` CLI flag (repeatable) to `change-cmd`
- [x] `test/gtd/change_test.clj` — Add test for property-setting

### xi extension (1 file)
- [x] `src/xi/ext/gtd.cljs` — Add `properties` param to `gtd_change` tool (passes `--property K=V` to CLI)
- [x] Rework `/gtd` command with subcommands:
  - `/gtd` (bare) → completion menu of non-done tasks → on select: set ACTIVE + XI_SESSION property, insert prompt
  - `/gtd recommend` → current prompt-based recommendation behavior
  - `/gtd cleanup` → prompt asking agent to review/cleanup tasks
- [x] Update system prompt to document ACTIVE state and `/gtd` subcommands

### Verification
- [x] Compile xi (`npx shadow-cljs compile main`) — 0 warnings
- [x] Run org-mode-agenda-cli tests — 405 tests, 1341 assertions, 0 failures
