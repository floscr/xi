# Frontend Components (clj-ui-framework)

The web client uses [clj-ui-framework](https://git.example.com/floscr/clj-ui-framework) for UI components. It's a cross-target (CLJ/CLJS/Squint) component library providing styled form controls, buttons, icons, badges, and more.

## Dependency

Declared as a git dep in `deps.edn`:

```clojure
com.example.git/clj-ui-framework
{:git/url "https://git.example.com/floscr/clj-ui-framework.git"
 :git/sha "<sha>"}
```

## Theme CSS

The framework produces a **built theme CSS file** (`dist/theme.css`) that must be copied into the xi project at `resources/public/theme.css`. It is **not** loaded automatically from the CLJS dependency — you must rebuild and copy it after changes:

```bash
cd /path/to/ui-framework
bb build-theme                          # generates dist/theme.css
cp dist/theme.css /path/to/xi/resources/public/theme.css
```

The xi project's `index.html` loads it as `<link rel="stylesheet" href="/theme.css">`.

## Updating the dependency

When adding or modifying components in the ui-framework:

1. Make changes in the ui-framework repo
2. Commit and push
3. Run `bb build-theme` to rebuild CSS
4. Copy `dist/theme.css` to `resources/public/theme.css` in xi
5. Update the `:git/sha` in xi's `deps.edn` to the new commit SHA (must be full 40-char SHA)
6. Restart shadow-cljs (needed for classpath changes)

## Available components

Components are required from the `ui.*` namespaces:

```clojure
(:require [ui.form :as form]
          [ui.button :as button]
          [ui.icon :as icon]
          [ui.badge :as badge]
          [ui.spinner :as spinner]
          [ui.lightbox :as lightbox])
```

### Forms

| Function | Description |
|----------|-------------|
| `form/form-input` | Text input (`:type :text`, `:email`, `:password`, `:date`, etc.) |
| `form/form-textarea` | Standard textarea (fixed min-height, vertical resize) |
| `form/form-textarea-auto` | Auto-growing textarea using CSS `field-sizing: content` |
| `form/form-select` | Select dropdown |
| `form/form-checkbox` | Checkbox with label |
| `form/form-radio-group` | Radio button group |
| `form/form-range` | Range slider |
| `form/form-file` | File input |
| `form/form-field` | Wrapper with label, hint, and error support |
| `form/form-group` | Input group (addons + input + button) |

### Auto-growing textarea

`form-textarea-auto` renders a `<textarea>` that starts at 1 row and grows with content up to `:max-rows` lines. It uses the CSS `field-sizing: content` property (Chrome 123+, Firefox 131+, Safari 18.4+) — no JavaScript needed.

```clojure
(form/form-textarea-auto {:placeholder "Message..."
                          :value text
                          :max-rows 3
                          :attrs {:on {:input (fn [e] ...)
                                       :keydown (fn [e] ...)}}})
```

The component sets an inline `max-height: calc(1.5em * N)` style from `:max-rows` (default 3). The base CSS class handles `field-sizing`, `resize: none`, and `overflow-y: auto`.

### Buttons

```clojure
(button/button {:variant :primary :size :sm} "Submit")
```

Variants: `:primary`, `:secondary`, `:ghost`, `:danger`. Sizes: `:sm`, `:md`, `:lg`.

### Icons

```clojure
(icon/icon {:icon-name :arrow-up :size :sm})
```

Uses Lucide icon set. See the ui-framework source for available icon names.

## CLJS target conventions

In the `:cljs` reader conditional (used by Replicant), components use:
- **Vectors for classes**: `{:class ["foo" "bar"]}` not `{:class "foo bar"}`
- **Nested event maps**: `{:on {:click handler}}` not `{:on-click handler}`
- **Keyword props**: `:type :text` not `:type "text"`

## Project-specific overrides

The xi project applies compose-box specific overrides in `resources/public/css/style.css`. These should only contain **layout/visual** properties (border, background, padding, font-size), not properties already handled by the ui-framework base classes:

```css
/* Good — only compose-specific overrides */
.compose-box .form-textarea-auto {
  flex: 1;
  border: none;
  background: transparent;
  padding: var(--size-2) 0;
  font-size: var(--font-base);
}

/* Bad — duplicates base class properties */
.compose-box .form-textarea-auto {
  field-sizing: content;    /* already in .form-textarea-auto */
  resize: none;             /* already in .form-textarea-auto */
  overflow-y: auto;         /* already in .form-textarea-auto */
}
```
