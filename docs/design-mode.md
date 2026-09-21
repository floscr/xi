# Design Mode (`xi.ext.design-mode`)

A *persistent* design mode for the MCP-controlled Chrome. Where `/pick` is a
one-shot picker that submits a blocking prompt, `/design` installs a resident
overlay on the page: you pick elements with **Ctrl+I or Ctrl+B in the
browser**, type an
instruction in a popover anchored next to the element, and each request runs in
a **background sub-agent** — the parent session is never blocked, so you keep
working while changes land. Progress shows in the Sub-agents panel.

```
/design            toggle design mode (asks which tab if several are open)
/design off        turn it off
Ctrl+Shift+D       keybinding for /design (TUI/web)

— then, in the browser —
Ctrl+I / Ctrl+B    toggle element picking (or click the Design pill)
click element      popover: type what you want changed
Enter / Send       queue it — a sub-agent picks it up; keep browsing
Choices            generate 3 design directions to choose from instead
Esc                popover → picking → browsing (mode stays on)
agents button      spinner while agents work; click for the list + Commit
```

The popover has two actions: **Send** applies the change directly (a sub-agent
edits the source); **Choices** (left of Send) instead asks a sub-agent to
generate a few *design directions* for the element — like the design-directions
skill — which you preview and pick from in a dialog. Any typed text is optional
guidance for either action.

## The agents dock (spinner · list · commit)

Left of the Design pill sits an **agents button**. It appears once you've
queued at least one request and reflects the design sub-agents' progress:

- **Spinner** while any design agent (or its commit) is working; otherwise a
  static sparkle icon, always with a running count.
- **Click it** to open a list of the design agents spawned this session — each
  row shows a status dot, the request label, and a per-state control:
  - *running* → a spinner
  - *done* (edit agent) → a **Commit** button
  - *done* (choices agent) → a **View N** button that opens the choices dialog
  - *committing* → a spinner
  - *committed* → a ✓ checkmark
  - *error* → an error label
- Clicking **Commit** sends a follow-up commit request for that agent. The
  watcher spawns a second sub-agent whose prompt carries the original agent's
  result summary; it reviews the working tree and creates one
  Conventional-Commit for that change (no push). The row shows a spinner while
  it runs and a ✓ when it lands.

The list and button are driven by the watcher: each poll pushes the tracked
agents' current status (`window.__xiDesignAgents`) into the page and calls
`window.__xiDesignRender`; Commit clicks are queued in
`window.__xiDesignCommitQueue` and drained on the next poll. Only agents
spawned *by this design session* appear here (commit agents are tracked against
their originating row, not listed separately).

## Design choices (generate directions → pick one)

Clicking **Choices** in the popover queues a request tagged `mode:"choices"`.
Instead of editing source, the sub-agent generates **3 self-contained design
directions** for the picked element and emits them as a trailing fenced-JSON
block (`[{label, note, html}]`) — mirroring the design-directions skill, but
scoped to one element.

The watcher parses those directions from the agent's result and surfaces them on
its dock row as a **View N** button. Clicking it opens a **dialog over the
page**: each direction renders in a sandboxed iframe preview card (its `html`
as `srcdoc`) with a label, note, and **Pick this** button.

Picking a direction round-trips back through `window.__xiDesignPickQueue`
(same pattern as the commit queue). The watcher drains it and enqueues a normal
`mode:"edit"` request whose instruction is "apply this chosen direction" plus
the direction's markup and the original element context. That reuses the
standard edit path — a fresh edit sub-agent implements the winner in the real
source, appearing as its own dock row with the usual **Commit** button. So the
full arc is: *Choices → View → Pick → edit agent → Commit*.

## How it works

Like the element picker, this is **not a standalone extension** — it's
installed *into* [`xi.ext.chrome-mcp`](chrome-mcp.md) via
`design-mode/install`, sharing the same `forward` caller the agent's browser
tools use. It requires `XI_CHROME_TOOLS`.

### The flow

```
/design → :ext.design-mode/toggle fx
  → list_pages           parse tabs; if >1, ask which (select_page)
  → evaluate_script      inject resident design script (cleanup + config + design.js)
  → watcher loop         evaluate_script every ~700ms:
      · pushes tracked agent statuses → window.__xiDesignAgents (+ re-render)
      · drains window.__xiDesignQueue → per request (mode edit|choices):
          take_screenshot → image/persist-image! → :subagent/spawn (tracked)
      · drains window.__xiDesignCommitQueue → per commit:
          :subagent/spawn a commit agent (prompt = original agent's result)
      · drains window.__xiDesignPickQueue → per pick:
          re-enqueue the chosen direction as a mode:edit request
      · re-injects when window.__xiDesignActive is gone
```

### Surviving page navigation

The injected script sets `window.__xiDesignActive`. A page navigation or
reload wipes all page globals, so the watcher's next poll sees the flag gone
and simply **re-injects** the (re-entrancy-guarded) script. That's the whole
persistence mechanism — the mode follows you across pages with no
chrome-devtools-mcp changes. Transient poll errors (evals racing a navigation)
are skipped and retried on the next tick.

### Requests become sub-agents

Each queued request carries the element's CSS selector, outerHTML (truncated),
computed styles, bounding rect, page URL, and your message. The watcher
screenshots the page, persists it to `~/.config/xi/uploads/` (readable by
sub-agent file tools), and dispatches `:subagent/spawn` directly — the same
no-confirmation path `/review` uses, since the request is user-initiated. The
sub-agent's prompt (`build-prompt`) tells it to locate the element's *source*
in the project, make the change there, and **not** drive the browser — the
user is actively using it, and the dev server hot-reloads the page.

Design mode is bound to the room where `/design` ran; `/design` in any room
toggles the single active instance off.

### The browser script is ClojureScript (squint), bundled to an IIFE

Same toolchain as the picker: `resources/design-mode/design.cljs` is compiled
with squint → esbuild into `resources/design-mode/design.js`, a **committed,
generated** self-contained IIFE (~20KB) injected as a string via
`evaluate_script`. Regenerate after editing:

```bash
bb design:build
```

The generated JS is baked into xi's node bundle at compile time by the
`inline-design-js` macro (`src/xi/ext/design_mode_js.clj`). shadow-cljs doesn't
track `design.js` as a source dependency — after `bb design:build`, touch
`src/xi/ext/design_mode.cljs` (or clean-compile) to re-inline.

### The UI

Styled to match xi's web client via the clj-ui-framework light-theme tokens
(`resources/public/theme.css`): neutral gray surfaces, violet accent, sans-serif
text, monoline lucide icons and subtle borders/shadows. The resident **Design**
pill sits bottom-right (violet accent while picking); submissions confirm with a
small "Sent — a sub-agent is on it" toast. While the mode is on, Ctrl+I and
Ctrl+B are swallowed by design mode (rich-text editors won't see them as
italic/bold); everything else passes through untouched.

## Enabling it

Same env as the chrome tools — see [chrome-mcp.md](chrome-mcp.md):

```bash
XI_CHROME_TOOLS=1 XI_CHROME_BROWSER_URL=http://127.0.0.1:9222 bb serve:restart
```

## Files

| Path | Role |
|------|------|
| `src/xi/ext/design_mode.cljs` | Watcher loop, sub-agent prompt building, `/design`, `install`. |
| `src/xi/ext/design_mode_js.clj` | Compile-time macro inlining `design.js`. |
| `resources/design-mode/design.cljs` | Browser-side resident script (squint ClojureScript). |
| `resources/design-mode/design.js` | **Generated** self-contained IIFE (squint → esbuild); committed. |
| `resources/design-mode/squint.edn` | squint config for the design-mode build. |
| `test/xi/ext/design_mode_test.cljs` | Unit tests (injection, poll parsing, prompt building, install). |

Wired into `xi.ext.chrome-mcp` (`src/xi/ext/chrome_mcp.cljs`), which owns the
shared MCP client.
