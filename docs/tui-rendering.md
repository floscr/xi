# TUI Rendering

Xi uses a retained-mode terminal UI with differential rendering, inspired by [Pi's TUI](https://mariozechner.at/posts/2025-11-30-pi-coding-agent/#toc_6). Content is appended to the terminal scrollback buffer (not fullscreen), so native scrolling and search work naturally.

## Architecture

```
tui/ansi.cljs        ANSI escape codes, colors, visible-width, word-wrap
tui/terminal.cljs    Raw mode, stdin splitting, cursor control, sync output
tui/grid.cljs        Cell grid: ANSI line parsing, cell-level diffing, minimal output
tui/core.cljs        Component protocol, Container, TUI engine, render scheduling
tui/components.cljs  Text, Spacer, Box, Loader
tui/editor.cljs      Raw-mode multi-line input editor
tui/markdown.cljs    Markdown → ANSI-formatted lines
```

## Component protocol

Every component is a map with three keys:

```clojure
{:render     (fn [width] -> ["line1" "line2" ...])
 :invalidate (fn [] -> nil)
 :handle-input (fn [data] -> nil)}  ;; optional, for focusable components
```

`render` takes the available width in columns and returns a vector of strings — one per terminal line. Strings may contain ANSI escape codes for colors/styling. Components cache their output and skip re-rendering when neither their content nor the width has changed.

`invalidate` clears the cache, forcing a fresh render on the next pass. Called on terminal resize.

`handle-input` receives raw stdin data (single keystrokes or escape sequences). Only the focused component receives input.

## Component tree

The root TUI is itself a Container. At startup, `cli.cljs` builds this tree:

```
root (Container)
├── chat-container (Container)
│   ├── Text "Xi — coding agent"
│   ├── Text "Model: claude-sonnet-..."
│   ├── Text "Type /quit to exit..."
│   ├── Spacer
│   │   ... messages, tool calls, loaders added here dynamically ...
│   ├── Text "you: hello"
│   ├── Spacer
│   ├── Markdown (streaming assistant response)
│   ├── Box (tool execution with dark background)
│   └── Loader "thinking..."
├── Spacer
└── Editor (focused — receives keyboard input)
```

A Container renders by collecting lines from all children in order. The TUI engine calls `render` on the root container to get the full frame.

## Cell-level differential rendering

Inspired by [comview](https://github.com/rockorager/comview) and its TUI library [vaxis](https://git.sr.ht/~rockorager/vaxis), which achieves fast rendering through cell-level diffing — comparing individual character cells between frames rather than whole lines. This means a spinner tick on a 200-column line writes ~15 bytes (cursor-move + 1 cell + reset) instead of rewriting the entire line.

The implementation lives in `tui/grid.cljs` and is a drop-in optimization — components still return ANSI line strings, the grid layer parses and diffs them transparently.

### Cell grid

A **cell** is a JS array `[char, style]` — the visible character and its accumulated ANSI SGR prefix (e.g. `"\033[31m\033[1m"` for bold red, or `""` for default). A **grid** is `{:width W :height H :cells js/Array-of-rows}` where each row is a `js/Array` of cells.

JS arrays are used throughout for performance — the hot path (parsing + diffing) avoids CLJS persistent data structures.

### Render pass

On each render pass (`do-render!` in `core.cljs`):

1. Call `(:render root-container width)` to get the new frame as a vector of ANSI line strings.
2. Apply selection highlighting if active.
3. Parse the display lines into a cell grid via `grid/frame->grid`. Each line is walked character-by-character, tracking SGR state across escape sequences. Non-SGR CSI sequences (cursor moves, etc.) are skipped.
4. Diff the new grid against the previous grid cell-by-cell. Contiguous runs of changed cells with the same style are coalesced into a single write.
5. Emit all changes in one `term/write!` call, wrapped in synchronized output (`CSI ?2026h` / `CSI ?2026l`).
6. Store the new grid as `previous-grid` and the raw lines as `previous-frame` (for selection text extraction).

On full repaint (resize, startup, resume from external command), `previous-grid` is nil. The renderer clears the screen first, then emits the full grid.

### Erasing the blank tail (width-desync robustness)

Every grid row is padded to the full terminal width, so a *short* line replacing
a *longer* one leaves a trailing run of blank cells. Diffing those cell-by-cell
assumes the grid's column model matches the terminal's exactly — but for glyphs
whose width is genuinely ambiguous across terminals/fonts (notably
text-default emoji upgraded by VS16, e.g. `⚠️` = U+26A0 U+FE0F), the terminal
may draw the glyph one column narrower than the width model. Everything after it
then sits one physical column off, and stale glyphs drift into the logically
*blank* tail — where old and new cells both read as spaces, so per-cell diffing
never clears them. The result is scattered orphan characters after a shorter
line (e.g. a heading left with `b  h  n  d …` trailing it).

To stay robust to any such disagreement, `emit-diff!` clears a **changed** row's
trailing blank run with a single erase-to-end-of-line (`\033[row;colH\033[0m\033[K`)
at the first blank column, instead of writing per-cell spaces. This wipes any
drifted orphans regardless of the width mismatch's direction. Rows whose tail
carries a background style (full-width tool boxes, etc.) have no default-blank
tail, so they are never erased this way — the background is preserved.

### Run coalescing

The diff emitter doesn't write cell-by-cell. It groups contiguous changed cells that share the same style into **runs**, emitting one cursor-move + style + character-sequence per run. For a typical frame where only the spinner changed, this produces a single short write. For a full repaint, runs span entire lines — roughly equivalent to the old line-by-line approach.

### Limitations

- **OSC sequences**: Inline OSC sequences (hyperlinks, etc.) are not handled. Xi doesn't embed these in rendered lines — OSC 52 clipboard writes go through `term/write!` directly.

## Character width

The cell-diff renderer positions every write with an absolute cursor move
(`\033[row;colH`). If the column a character *starts* at is computed even one
off, the write lands on the wrong cell and "eats" the adjacent glyph — you see
corruptions like `No imeout` instead of `No timeout`. So the single source of
truth for **how many terminal columns a code point occupies** has to be correct
for every character, not just the common ones.

That truth lives in `tui/ansi.cljs`:

- `zero-width?` — combining marks, variation selectors, zero-width
  joiners/spaces → **0 columns**.
- `wide?` — East Asian **Wide/Fullwidth** *or* **Emoji_Presentation=Yes** →
  **2 columns**.
- `char-width` composes them: `< U+0300` (ASCII/Latin-1) is a fast path to 1,
  then `zero-width?` → 0, `wide?` → 2, else 1. `visible-width` /
  `frame->grid` sum this across a string.

### Why the wide table is generated, not hand-written

`wide?` used to be a hand-curated `or` of ~20 code-point ranges. Every time a
new emoji block shipped (the colored circles `🟠🟡🟢` at U+1F7E0+, for example)
it was missing from the list, terminals rendered it 2 wide, our width said 1,
and the cursor-diff renderer desynced and ate characters. Hand-maintaining the
list is "waiting for the next gap."

Instead the wide set is **derived from the authoritative Unicode data** and
checked in as a generated table:

- `scripts/gen-char-width.mjs` fetches `EastAsianWidth.txt` and
  `emoji/emoji-data.txt` for a pinned Unicode version, takes
  (EAW W/F) ∪ (Emoji_Presentation=Yes), merges adjacent ranges, and writes
  `src/xi/tui/char_width_data.cljs`.
- `char_width_data.cljs` is **generated — do not edit by hand**. It is a flat
  sorted `#js [lo0 hi0 lo1 hi1 …]` int array (122 ranges at Unicode 16.0.0).
- `wide?` **binary-searches** that array (~7 comparisons for the whole BMP+SMP
  range space), and is only ever reached for non-ASCII code points thanks to
  the `< U+0300` fast path — so there is no measurable cost versus the old
  linear `or`.

Note a naive "treat the whole emoji plane as wide" would be wrong: within
U+1F700+ there are genuinely narrow blocks (alchemical symbols U+1F700,
geometric-shapes-extended arrows U+1F780, legacy computing U+1FB00). The
generated table keeps those at width 1.

### Bumping Unicode versions

When a new Unicode release adds emoji/CJK, regenerate the table:

```bash
node scripts/gen-char-width.mjs      # bump UNICODE_VERSION in the script first
```

Commit the regenerated `src/xi/tui/char_width_data.cljs` alongside the version
bump. No `wide?` code changes are needed — only the data.

## Render scheduling

Components call `request-render!` when their state changes (e.g., `(:set-text text-comp "new")`). Renders are debounced to 16ms via `setTimeout` so rapid updates (like streaming text) coalesce into a single repaint. `render-now!` bypasses the debounce for immediate display after user actions.

## Terminal layer

`terminal.cljs` manages raw mode:

- **Raw mode**: `stdin.setRawMode(true)` — every keypress is delivered immediately, not line-buffered.
- **Input splitting**: Batched stdin data (from pipes or fast typing) is split into individual escape sequences and characters before forwarding to the focused component.
- **Bracketed paste**: `CSI ?2004h` on start, `CSI ?2004l` on stop. Pasted text arrives wrapped in `CSI 200~` / `CSI 201~` markers so the editor can insert it without treating each character as a command.
- **Cursor hidden**: The hardware cursor is hidden during rendering. The editor draws its own cursor using reverse video (`CSI 7m`).

## Built-in components

| Component | Constructor | Description |
|-----------|------------|-------------|
| **Text** | `make-text` | Word-wrapped text with optional padding and background. Caches rendered output. |
| **Spacer** | `make-spacer` | Renders N empty lines. |
| **Box** | `make-box` | Wraps children with horizontal/vertical padding and an optional background color function. Used for tool call output. |
| **Loader** | `make-loader` | Animated braille spinner (⠋⠙⠹...) with a message. Ticks every 80ms, calling `request-render!` each frame. |
| **Markdown** | `make-markdown` | Renders markdown text with ANSI formatting — headers bold, `code` cyan, code blocks dimmed, checkboxes ✓/○. |
| **Editor** | `make-editor` | Multi-line raw-mode input. Cursor movement, history, kill-line, bracketed paste. Renders a `───` border above the prompt. |

## Input handling

`core.cljs` receives raw stdin from the terminal layer and dispatches it. Modal components (diff viewer, etc.) with `:capture-all-input true` receive all keyboard input directly. In normal mode, core handles scroll keys (Page Up/Down, Shift+Up/Down) itself, then forwards everything else to the focused component (typically the editor).

For non-scroll keys, core auto-snaps the viewport to the bottom before forwarding — so typing while scrolled brings you back to the latest content. **Escape is the exception**: when scrolled, escape is consumed by core (scroll-to-bottom) and not forwarded. This prevents the editor's escape handler from misinterpreting it.

### Escape key state machine

The escape key has a priority-ordered state machine split across two layers:

```
┌─────────────┐     ┌──────────────────────────────────┐
│  tui/core    │     │  client/tui  (handle-escape!)    │
│              │     │                                  │
│  Scrolled?  ─┼─yes─▶  scroll to bottom (consumed)    │
│      │ no    │     │                                  │
│      ▼       │     │  Modal open? ──yes──▶ dismiss    │
│  forward to  │     │      │ no                        │
│  editor      │     │      ▼                           │
│              │     │  Agent busy? ──yes──▶ abort       │
│              │     │      │ no                        │
│              │     │      ▼                           │
│              │     │  Editor empty? ─yes─▶ tree view  │
│              │     │      │ no                        │
│              │     │      ▼                           │
│              │     │    no-op                         │
└──────────────┘     └──────────────────────────────────┘
```

| State | Escape action | File |
|-------|---------------|------|
| Viewport scrolled up | Scroll to bottom | `tui/core.cljs` (consumed, not forwarded) |
| Modal buffer open | Dismiss / refocus modal | `client/tui.cljs` |
| Agent running | Abort agent turn | `client/tui.cljs` |
| Idle + editor empty | Show tree selector | `client/tui.cljs` |
| Idle + editor has text | No-op | `client/tui.cljs` |

The "scrolled" state is handled at the core layer because core already manages scroll state and auto-snap. All other states are handled in the client's `handle-escape!` function. This separation means the editor's escape handler can assume the viewport is always at the bottom.

## Agent turn flow

When the user submits a prompt:

1. A `Text` ("you: ...") is added to the chat container.
2. The `Loader` is added and started (spinner animation).
3. As the model streams text, the loader is replaced with a `Markdown` component whose text grows incrementally.
4. On tool calls, a `Box` with dark background is added showing the tool name, its output, and duration.
5. Between tool calls, the loader reappears until the next event.
6. When the turn completes, the loader is removed and the editor is ready for the next prompt.

All of this happens by mutating the chat container's children list and calling `render-now!` or `request-render!`. The differential renderer picks up the changes and repaints only what moved.
