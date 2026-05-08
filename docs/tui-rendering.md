# TUI Rendering

Xi uses a retained-mode terminal UI with differential rendering, inspired by [Pi's TUI](https://mariozechner.at/posts/2025-11-30-pi-coding-agent/#toc_6). Content is appended to the terminal scrollback buffer (not fullscreen), so native scrolling and search work naturally.

## Architecture

```
tui/ansi.cljs        ANSI escape codes, colors, visible-width, word-wrap
tui/terminal.cljs    Raw mode, stdin splitting, cursor control, sync output
tui/core.cljs        Component protocol, Container, TUI engine, diff rendering
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

## Differential rendering

On each render pass (`do-render!` in `core.cljs`):

1. Call `(:render root-container width)` to get the new frame as a vector of lines.
2. Compare with `previous-lines` to find the first line that differs.
3. If nothing changed, skip. Otherwise:
   - Wrap output in synchronized output (`CSI ?2026h` / `CSI ?2026l`) so the terminal buffers all writes and flushes atomically — prevents flicker.
   - Move the cursor up from its current position to the first changed line.
   - Clear and rewrite each line from the diff point to the end.
   - If the new frame is shorter than the previous one, clear the leftover lines.
4. Store the new lines as `previous-lines` for the next pass.

On terminal resize, `previous-width` is reset to 0 which forces a full re-render (line 0 is always the first diff).

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

## Agent turn flow

When the user submits a prompt:

1. A `Text` ("you: ...") is added to the chat container.
2. The `Loader` is added and started (spinner animation).
3. As the model streams text, the loader is replaced with a `Markdown` component whose text grows incrementally.
4. On tool calls, a `Box` with dark background is added showing the tool name, its output, and duration.
5. Between tool calls, the loader reappears until the next event.
6. When the turn completes, the loader is removed and the editor is ready for the next prompt.

All of this happens by mutating the chat container's children list and calling `render-now!` or `request-render!`. The differential renderer picks up the changes and repaints only what moved.
