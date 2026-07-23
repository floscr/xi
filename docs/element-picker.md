# Element Picker (`xi.ext.element-picker`)

Pick a DOM element in the MCP-controlled Chrome, type a message inline, and the
extension sends that element's HTML, CSS selector, computed styles, and a page
screenshot to the agent as the next prompt. The model gets direct visual + DOM
context about exactly what you mean — no describing the element in prose.

```
/pick                  open the picker (asks which tab if several are open)
/pick fix this layout  open with a pre-filled message
Ctrl+Shift+I           keybinding for /pick
```

## How it works

The picker is **not a standalone extension**. It's installed *into*
[`xi.ext.chrome`](chrome-mcp.md), which owns the shared `chrome-devtools-mcp`
stdio client. `xi.ext.chrome/create` merges in `element-picker/install`, passing
the same `forward` caller the agent's browser tools use. So the picker requires
`XI_CHROME_TOOLS` to be set — the same env that enables the chrome tools.

### Targeting a tab

The picker acts on chrome-devtools-mcp's **selected page**. When more than one
tab is open, `run-picker` first parses `list_pages` (`parse-pages`) and raises a
`:select` dialog listing every tab (the current one tagged `[current]`); your
choice is applied via `select_page` before injection. With a single tab — or in
a headless server with no `ask!` available — it skips the dialog and uses the
already-selected page. The `[selected]` tab is also surfaced in the status line
so you can see which page you're picking on. (Tabs can share a URL, so the
dialog labels each with its title + URL to disambiguate.)

### The flow

```
/pick → :ext.element-picker/run fx
  → list_pages            parse tabs; if >1, ask which (select_page)
  → list_pages            (status: which tab is selected)
  → evaluate_script       inject picker overlay (cleanup + config + picker.js)
  → poll evaluate_script  every 400ms for window.__xiPickerResult / …Cancelled
  → take_screenshot       one viewport PNG of the (now clean) page
  → :prompt/submit        text (build-message) + resized :images
```

The browser-side script draws a transparent overlay plus a visible banner
("🎯 xi picker — hover & click an element · Esc to cancel"), highlights elements
on hover, and on click shows a panel where you type the message. Selecting
supports multiple elements; the result is stashed on `window.__xiPickerResult`
for the poll loop to read.

### The browser script is ClojureScript (squint), bundled to a self-contained IIFE

The picker's browser-side code is written in ClojureScript at
`resources/element-picker/picker.cljs` and compiled with
[squint](https://github.com/squint-cljs/squint) → [esbuild](https://esbuild.github.io/)
into `resources/element-picker/picker.js` — a **committed, generated** artifact.

Why this toolchain (and not xi's own shadow-cljs `:browser` build): the script
is injected as a *string* into an arbitrary remote page via `evaluate_script`,
so it must be one self-contained blob with **no `import`s**. squint's runtime is
tiny — esbuild tree-shakes it down so the whole bundle is ~10KB (a shadow-cljs
browser build would drag in `cljs.core`, 100KB+, on every pick). squint emits an
ESM `import` of `squint-cljs/core.js`; the esbuild `--bundle --format=iife` step
inlines it into an IIFE that runs on eval.

Regenerate after editing `picker.cljs`:

```bash
bb picker:build   # squint compile + esbuild → resources/element-picker/picker.js
```

`squint-cljs` and `esbuild` are **devDependencies** (build-time only) — xi's
single-runtime-dep rule is untouched, since the generated `picker.js` is what
ships.

#### squint gotchas (learned porting picker.js → picker.cljs)

- **No `js->clj` / `clj->js`** — squint works on JS-native data. Read the config
  object (`window.__XI_PICKER_CFG__`) via interop (`(.-colors cfg)`), never
  `js->clj`. A stray `js->clj` compiles to an undefined `js__GT_clj` reference.
- **Empty string is truthy** in CLJS `when`/`and` (only `nil`/`false` are
  falsy), unlike JS where `''` is falsy. The original `if (CFG.prefillMessage)`
  became `(when … (not-empty (.-prefillMessage cfg)))` to preserve behavior.

### JS is inlined into the CLJS bundle at compile time

The generated `picker.js` is baked into xi's node bundle by the
`inline-picker-js` macro (`src/xi/ext/element_picker_js.clj`), mirroring
`xi.ext.chrome-defs` / `xi.highlight.bundle`. `resources/` isn't on the
classpath, so the macro reads the file by repo-relative path at compile time —
no runtime path dependency. Because shadow-cljs doesn't track `picker.js` as a
source dependency, a fresh `bb picker:build` won't retrigger a recompile of
`element_picker.cljs` on its own — touch that file (or clean-compile) to
re-inline.

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
| `resources/element-picker/picker.cljs` | Browser-side picker source (squint ClojureScript). |
| `resources/element-picker/picker.js` | **Generated** self-contained IIFE (squint → esbuild); committed. |
| `resources/element-picker/squint.edn` | squint config for the picker build. |
| `test/xi/ext/element_picker_test.cljs` | Unit tests (injection, parsing, install, message building). |

The picker is wired into `xi.ext.chrome` (`src/xi/ext/chrome.cljs`), which owns
the shared MCP client.
