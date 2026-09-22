# Style Editor (`xi.ext.style-editor`)

An **agent-driven** counterpart to the [element picker](element-picker.md). The
model calls a `style_editor` tool with a CSS selector and a list of controls
(sliders / color pickers); the extension injects a floating panel into the
MCP-controlled Chrome, the user tunes the element's styles **visually** (each
control live-applies to the element's inline style), and on commit the final
CSS values are returned to the model as the tool result — so the model can make
the matching source edits.

Where the element picker is *user*-triggered (`/pick`) and sends context *to*
the model, the style editor is *model*-triggered: the model asks for a panel,
the human dials in the look, and the numbers come back.

```
You:   give me sliders for border-radius, background color + opacity on the hero card
Model: (calls) style_editor {selector ".hero-card"
                             controls [{property "borderRadius" min 0 max 40}
                                       {property "backgroundColor" type "color"}]}
       → floating panel appears on the page
You:   drag the sliders until it looks right, click Apply
Model: gets {borderRadius "18px", backgroundColor "rgba(30, 30, 46, 0.85)"}
       → edits your CSS/component source to match
```

## Launching from the element picker

Besides asking the model directly, you can start the style editor straight from
the [element picker](element-picker.md). Run `/pick`, hover-select an element,
type what you want to tune in the message box, then click the **🎨 Style
editor** button (next to **Send to xi**) instead of **Send to xi**.

The picker flags its result with `mode: "style-editor"`; the node side
(`xi.ext.element-picker`) then submits a prompt that hands the model the exact
picked selector and asks it to open `style_editor` with controls tailored to
your typed message — so the round-trip is the same as if you'd asked by hand,
but the selector is guaranteed correct. This is a **model-mediated** handoff
(the picker doesn't call the tool itself): the model chooses which properties /
control types fit your request.

## The tool

`style_editor` — arguments:

| Arg | Type | Notes |
|-----|------|-------|
| `selector` | string (required) | CSS selector of the element to edit. Acts on chrome-devtools-mcp's currently *selected* page. |
| `title` | string | Optional panel title. |
| `controls` | array (required) | One control per CSS property to tune. |

Each control:

| Key | Type | Notes |
|-----|------|-------|
| `property` | string (required) | CSS property in **camelCase** (`borderRadius`, `backgroundColor`, `opacity`). |
| `label` | string | Human label (defaults to the property name). |
| `type` | `"range"` \| `"color"` \| `"opacity"` | Omit to infer: `opacity` → opacity slider, anything with `color` → color picker, else a range slider. |
| `min` `max` `step` | number | Range bounds (range only). Defaults: `min` 0, `max` `max(100, current)`, `step` 1. |
| `unit` | string | Appended to the range value (range only; default `"px"`). |

### Choosing selectors (agents: always do this)

**Prefer a stable class selector, not a positional one.** When you open the
panel, target elements by the **class whose CSS rule you'll ultimately edit**
(e.g. `.project-card`, `.project-card-info`) rather than a brittle positional
chain like `#app > div > aside > nav > div:nth-of-type(2) > div:nth-of-type(2)`.
The element picker hands you a positional selector because that's what uniquely
identifies the picked node — **translate it to the underlying class** (it's in
the picked element's HTML / class list) before calling the tool. Reasons:

- The **scope toggle live-previews across every element sharing the target's
  base class**, so a class selector lets the user see the real, site-wide result
  while dragging. A positional selector matches exactly one node, so the preview
  can only ever touch that one node.
- The class rule is what you edit in the source anyway, so the selector matches
  your eventual edit target.
- Positional `nth-of-type` chains break the moment the DOM shifts.

Fall back to the positional selector only when the element genuinely has no
usable class.

**One control per element.** When the properties you're tuning live on different
elements, give each control its own `selector` (the top-level `selector` is just
the default). Classic example: wrapper padding on `.project-card` **and** the
gap between a title and subtitle on the inner `.project-card-info` — two
controls, two selectors, one panel.

Control types:

- **range** — a slider initialized from the element's *computed* value; emits
  `<value><unit>` (e.g. `18px`).
- **color** — a hue/saturation plane + opacity track with hex / rgb / hsl
  formats; emits a color string (`rgba(…)` when the alpha is < 1), so
  "background color" *and* "background opacity" are one control.
- **opacity** — a 0–100 slider mapped to the element `opacity` (0–1).

The panel itself is **[dialkit](frontend.md)** (`window.__uiDial` from
clj-ui-framework, bundled into the injected script) — a leva-style floating,
draggable value-tuning panel. It is draggable by its header; commit and discard
are the **✓ Apply** / **✕ Cancel** buttons at the bottom (Cancel restores the
element's original inline styles).

Alongside the controls the panel also has:

- **Apply to shared class** — a toggle (default *on* when the element
  has a class). It drives **both** the live preview and the committed scope:
  - *checked* — the preview live-applies to **every element sharing the
   target's base class** (the target's first class token), so you see the
   change across all matching nodes as you drag; the committed result tells the
   model to edit the shared **CSS rule / class**. Toggling it back off restores
   the other elements' inline styles.
   - *unchecked* — the preview and the committed edit target **only this one
   node**.
  The element's class list is passed along so the model knows which rule to
  edit.
- **Refine (optional)** — a free-text box for extra instructions that ride back
  with the committed values (e.g. "also tighten the line-height").

Committing returns a markdown list of `property: value` pairs, the scope
instruction (class rule vs. single node), and any refine text.

## How it works

Like the element picker, this is **not a standalone extension** — it's installed
*into* [`xi.ext.chrome-mcp`](chrome-mcp.md), which owns the shared
`chrome-devtools-mcp` stdio client. `xi.ext.chrome-mcp/create` merges in
`style-editor/install`, passing the same `forward` caller the agent's browser
tools use. Both the chrome proxy tools and this tool contribute
`:tool-definitions` / `:tool-registry`, so `create` combines them explicitly
(a plain map merge would clobber one). It requires `XI_CHROME_TOOLS` — the same
env that enables the chrome tools.

### The flow

```
style_editor {selector, controls}
  → evaluate_script       inject panel (cleanup + config + style-editor.js)
  → poll evaluate_script  every 400ms for window.__xiStyleEditorResult / …Cancelled
  → return tool result    committed {values, refine, applyToClass, classes}, or cancel/timeout
```

The browser-side script finds each control's element via `document.querySelector`,
initializes the control from `getComputedStyle`, and live-applies to
`element.style` on input. When the **scope toggle** is on it applies to every
element matching the target's base class (`querySelectorAll('.' + firstClass)`),
stashing those elements' original inline values (`extraOrig`) so toggling scope
off — or Cancel — restores them. It stashes the committed values on
`window.__xiStyleEditorResult` for the poll loop. Cancel restores the original
inline styles (both the target and any class-wide preview elements). The poll
times out after 5 minutes.

### The browser script is ClojureScript (squint), bundled to a self-contained IIFE

Same toolchain as the element picker (and same reasons — the script is injected
as a *string* into an arbitrary page, so it must be one self-contained blob with
no `import`s). Source at `resources/style-editor/style-editor.cljs`, compiled
with squint → esbuild into `resources/style-editor/style-editor.js` — a
**committed, generated** artifact (~37KB, since the dialkit panel it `require`s
is bundled in). The overlay pulls in dialkit by requiring the framework's `dial`
namespace directly — `resources/style-editor/squint.edn` adds
clj-ui-framework's `src/ui/js` to `:paths`, and esbuild `--bundle` tree-shakes
and links it (plus `squint-cljs`) into the IIFE. No vendoring.

dialkit is token-driven CSS. Since the target page doesn't load
clj-ui-framework's stylesheet, the node ext injects a `<style>` first: the
tokens dial.css uses are resolved (dark theme) and scoped to `.dialkit-root` by
`bb dialkit:css` into `resources/dialkit/dial.css`, inlined via the
`inline-dialkit-css` macro (`src/xi/ext/dialkit_css.clj`). Rerun `bb dialkit:css`
after bumping the clj-ui-framework checkout.

Regenerate after editing the `.cljs`:

```bash
bb dialkit:css          # regenerate the injectable dialkit stylesheet (only after a framework bump)
bb style-editor:build   # squint compile + esbuild → resources/style-editor/style-editor.js
```

The generated JS is baked into xi's node bundle by the `inline-style-editor-js`
macro (`src/xi/ext/style_editor_js.clj`). Because shadow-cljs doesn't track the
generated `.js` as a source dependency, a fresh `bb style-editor:build` won't
retrigger a recompile of `style_editor.cljs` on its own — touch that file (or
clean-compile) to re-inline.

See [element-picker.md](element-picker.md) for the shared squint gotchas
(no `js->clj`/`clj->js`, empty-string truthiness).

## Enabling it

Same env as the chrome tools — see [chrome-mcp.md](chrome-mcp.md). Set the env
before starting the server and pick it up with `bb serve:restart`:

```bash
XI_CHROME_TOOLS=1 XI_CHROME_BROWSER_URL=http://127.0.0.1:9222 bb serve:restart
```

## Files

| Path | Role |
|------|------|
| `src/xi/ext/style_editor.cljs` | The tool: MCP orchestration, parsing, result formatting, `install`. |
| `src/xi/ext/style_editor_js.clj` | Compile-time macro inlining `style-editor.js`. |
| `resources/style-editor/style-editor.cljs` | Browser-side source (squint ClojureScript). |
| `resources/style-editor/style-editor.js` | **Generated** self-contained IIFE (squint → esbuild); committed. |
| `resources/style-editor/squint.edn` | squint config for the build (adds clj-ui-framework `src/ui/js` to `:paths` for `dial`). |
| `resources/dialkit/dial.css` | **Generated** injectable dialkit stylesheet (`bb dialkit:css`); committed. |
| `scripts/gen-dialkit-css.clj` | Resolves clj-ui-framework tokens + dial.css → `resources/dialkit/dial.css`. |
| `src/xi/ext/dialkit_css.clj` | Compile-time macro inlining `resources/dialkit/dial.css`. |
| `test/xi/ext/style_editor_test.cljs` | Unit tests (config, injection, parsing, formatting, install). |

The tool is wired into `xi.ext.chrome-mcp` (`src/xi/ext/chrome_mcp.cljs`), which owns the
shared MCP client.
