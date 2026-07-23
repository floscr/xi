# Element Picker (`xi.ext.element-picker`)

Pick a DOM element in the MCP-controlled Chrome, type a message inline, and the
extension sends that element's HTML, CSS selector, computed styles, and a page
screenshot to the agent as the next prompt. The model gets direct visual + DOM
context about exactly what you mean — no describing the element in prose.

```
/pick                  open the picker on the selected page
/pick fix this layout  open with a pre-filled message
Ctrl+Shift+I           keybinding for /pick
```

## How it works

The picker is **not a standalone extension**. It's installed *into*
[`xi.ext.chrome`](chrome-mcp.md), which owns the shared `chrome-devtools-mcp`
stdio client. `xi.ext.chrome/create` merges in `element-picker/install`, passing
the same `forward` caller the agent's browser tools use. So the picker requires
`XI_CHROME_TOOLS` to be set — the same env that enables the chrome tools.

### Targeting the selected page

The picker always acts on chrome-devtools-mcp's **currently selected page**.
That's the only unambiguous target: tabs can share a URL, so there's no reliable
way to match a specific tab from the outside. `run-picker` calls `list_pages`
first and surfaces the `[selected]` tab in the status line so you can see which
page you're picking on.

### The flow

```
/pick → :ext.element-picker/run fx
  → list_pages            (status: which tab is selected)
  → evaluate_script       inject picker overlay (cleanup + config + picker.js)
  → poll evaluate_script  every 400ms for window.__xiPickerResult / …Cancelled
  → take_screenshot       one viewport PNG of the (now clean) page
  → :prompt/submit        text (build-message) + resized :images
```

The browser-side script (`resources/element-picker/picker.js`) draws a
transparent overlay plus a visible banner ("🎯 xi picker — hover & click an
element · Esc to cancel"), highlights elements on hover, and on click shows a
panel where you type the message. Selecting supports multiple elements; the
result is stashed on `window.__xiPickerResult` for the poll loop to read.

### JS is inlined at compile time

`picker.js` is baked into the bundle by the `inline-picker-js` macro
(`src/xi/ext/element_picker_js.clj`), mirroring `xi.ext.chrome-defs` /
`xi.highlight.bundle`. `resources/` isn't on the classpath, so the macro reads
the file by repo-relative path at compile time — there is no runtime path
dependency.

## Pitfall: `:image/process` is an effect, not an event

The screenshot must be resized before submission. `:image/process` is an
**effect** (invoked as an effect vector `[:image/process …]`), *not* an event —
dispatching `{:type :image/process …}` as an event is silently dropped. So
`submit!` resizes inline via `xi.image/process-images` and dispatches
`:prompt/submit` with `:images` directly (that event accepts `:images`). This is
why the picked element's message *and* its screenshot both reach the agent.

## Enabling it

Same env as the chrome tools — see [chrome-mcp.md](chrome-mcp.md). Set the env
before starting the server and pick it up with `bb serve:restart`:

```bash
XI_CHROME_TOOLS=1 XI_CHROME_BROWSER_URL=http://127.0.0.1:9222 bb serve:restart
```

## Files

| Path | Role |
|------|------|
| `src/xi/ext/element_picker.cljs` | The picker: MCP orchestration, parsing, message building, `install`. |
| `src/xi/ext/element_picker_js.clj` | Compile-time macro inlining `picker.js`. |
| `resources/element-picker/picker.js` | The browser-side overlay/picker script. |
| `test/xi/ext/element_picker_test.cljs` | Unit tests (injection, parsing, install, message building). |

The picker is wired into `xi.ext.chrome` (`src/xi/ext/chrome.cljs`), which owns
the shared MCP client.
