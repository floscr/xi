# Diff Buffer

## Tasks

- [x] Create `xi.tui.diff-buffer` with core diff viewer component
- [x] Add `capture-all-input` support to `tui/core` for modal components
- [x] Add `scroll-to-offset!` and `get-scroll-offset` to `tui/core`
- [x] Wire up diff buffer into `xi.client.tui` as new buffer type
- [x] Register `/diff` command (session / staged / unstaged / arbitrary ref)
- [x] Track session-touched files from tool events (edit/write)
- [x] Capture git HEAD at session start for session diff baseline
- [x] Implement diff parsing (unified diff → structured data)
- [x] Implement diff rendering with syntax highlighting and line numbers
- [x] Implement keybindings (j/k, gg/G, ]c/[c, ]f/[f, Ctrl-d/u, q, :)
- [x] Implement `:` to temporarily activate editor prompt (Escape returns)
- [x] Add Diff to `/buffers` menu with proper focus handling
- [x] Compilation passes, all tests pass

## Architecture

### New concepts:
- **`capture-all-input`** — flag on focused component; when set, tui/core routes
  ALL keyboard input (including page keys) to the component, disables auto-snap-to-bottom,
  and forwards mouse scroll to `:handle-scroll`.

### Files changed:
- `src/xi/tui/diff_buffer.cljs` — NEW: diff parsing, rendering, navigation component
- `src/xi/tui/core.cljs` — Added: `scroll-to-offset!`, `get-scroll-offset`, `capture-all-input` handling
- `src/xi/client/tui.cljs` — Added: `/diff` command, session tracking, modal buffer management

### Commands:
- `/diff` — session changes (git diff from HEAD at session start)
- `/diff staged` — staged changes
- `/diff unstaged` — unstaged changes
- `/diff HEAD~3` (or any ref) — diff against arbitrary git ref
