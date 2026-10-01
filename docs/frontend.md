# Frontend Components (clj-ui-framework)

The web client uses [clj-ui-framework](https://github.com/floscr/clj-ui-framework) for UI components — a cross-target (CLJ/CLJS/Squint) library of styled form controls, buttons, icons, badges, menus, and more. Always use its components instead of raw `[:input]`/`[:button]`/`[:select]` elements.

Its `AGENTS.md` covers per-target pitfalls, theming/tokens and adding components; its generated `docs/components.md` (`bb list-components` / `bb list-icons`) is the always-current list of components, props and icons. For an exact prop shape, read the source in the pinned gitlib: `~/.gitlibs/libs/io.github.floscr/clj-ui-framework/<sha>/src/ui/`.

## Dependency

Declared as a git dep in `deps.edn`:

```clojure
io.github.floscr/clj-ui-framework
{:git/url "https://github.com/floscr/clj-ui-framework.git"
 :git/sha "<sha>"}
```

## Built assets (theme CSS + JS runtime)

Two framework build outputs are **committed copies** in xi — they are not
loaded from the CLJS dependency, so re-copy them after framework changes:

```bash
cd ~/Code/Projects/clj-ui-framework
bb build-theme        # → dist/theme.css
bb build-js-runtime   # → dist/ui-runtime.js (context menu etc.)
cp dist/theme.css     ~/Code/Projects/xi/resources/public/theme.css
cp dist/ui-runtime.js ~/Code/Projects/xi/resources/public/ui-runtime.js
```

`resources/public/index.html` loads both (`/theme.css`, `/ui-runtime.js`).

## Updating the dependency

1. Make changes in the framework repo, commit and push
2. Rebuild + copy the built assets (above) if CSS or the JS runtime changed
3. Update the `:git/sha` in xi's `deps.edn` (full 40-char SHA)
4. Restart shadow-cljs (classpath change)

## CLJS target conventions

In the `:cljs` reader conditional (used by Replicant), components use:
- **Vectors for classes**: `{:class ["foo" "bar"]}` not `{:class "foo bar"}`
- **Nested event maps**: `{:on {:click handler}}` not `{:on-click handler}`
- **Keyword props**: `:type :text` not `:type "text"`

## Project-specific overrides

xi's overrides live in `resources/public/css/style.css`. They should only contain **layout/visual** properties (border, background, padding, font-size), never properties the framework's base classes already set:

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
