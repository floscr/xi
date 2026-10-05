# Tree-sitter Outlines (`:treesitter` extension)

Context-token saving for code reads, modeled on [maki](https://maki.sh/)'s
`index` tool: reading a **large supported source file** returns a compact
structural outline (imports, type definitions, function signatures — each with
a 1-based `[start-end]` line range) instead of the full contents. The model
then pulls only the definitions it actually needs. In maki's measurements,
file reads are ~65% of all context tokens, and skeleton-first reading saves a
large fraction of that.

Two pieces:

1. **A parse layer** (`xi.ext.treesitter.parse`) — [web-tree-sitter](https://github.com/tree-sitter/tree-sitter/tree/master/lib/binding_web)
   (tree-sitter compiled to WASM) running in-process under Bun, with one
   `.wasm` grammar per language. Nothing to install, no native binary, no
   per-platform build: the runtime and the grammars are vendored in
   `resources/treesitter/` and ship with Xi.
2. **An extension** (`src/xi/ext/treesitter/`) — overrides the builtin `read`
   tool (a `:tool-registry` entry of the same name, which wins over the
   builtin) to return the outline, falling back to the builtin read; plus a
   `read_source` tool for literal code. Being a plain tool, not a gate, a rules
   `:allow` on `read` can't skip the outline.

The extension is a factory that returns `nil` when `resources/treesitter` can't
be found, in which case reads silently stay plain. The runtime and all grammars
load in the background at startup (about 40 ms, ~40 MB resident).

## Where the files live

```
resources/treesitter/
  manifest.json            — pinned runtime version + each grammar's repo/rev
  web-tree-sitter.cjs      — the runtime (+ web-tree-sitter.wasm, .LICENSE)
  grammars/<lang>.wasm     — one grammar per language
```

Xi looks in `$XI_TREESITTER_DIR` when set, else `resources/treesitter` next to
the compiled script, else under the working directory. The directory must
contain `web-tree-sitter.cjs`, `web-tree-sitter.wasm` and `grammars/`.

### Rebuilding or updating grammars

```bash
bb treesitter:build               # runtime + every grammar in manifest.json
bb treesitter:build python rust   # only these grammars
```

Each grammar is fetched at the exact `rev` in `manifest.json` and compiled with
`tree-sitter build --wasm` (tree-sitter CLI ≥ 0.26; it downloads its own
wasi-sdk, so no emscripten or Docker). The same inputs produce byte-identical
`.wasm` files. Set `$TREE_SITTER` when the plain binary can't run, e.g. on
NixOS, where the downloaded wasi-sdk needs an FHS shell:

```bash
TREE_SITTER="steam-run tree-sitter" bb treesitter:build
```

To bump a grammar, change its `rev` in `manifest.json`, rebuild it, and run
`bb test` — the extractor tests catch node-type changes. A grammar must be
built for the pinned `web-tree-sitter` version (the ABI has to match), so bump
the runtime and rebuild every grammar together.

## What the model sees

For a `read` of a large source file, instead of the file contents:

```
[src/foo/bar.ts — 412 lines. Structural outline; [n-m] are 1-based line ranges.
 Literal code: read_source(path, symbol) for one definition, read(path, offset, limit)
 for a range (offset is 0-based), read_source(path) for the whole file.]

imports: [1-6]
  { request } from './http'
  type { Config } from './config'

types:
  export interface Config [8-14]
    apiUrl: string
    retries: number
fns:
  export function createClient(config: Config): Client [16-42]
  async function retry<T>(fn: () => Promise<T>): Promise<T> [44-61]
```

Container entries (classes, interfaces, structs, impls…) list their members
indented one level; members are capped at 8 with a `[N more truncated]` line.
Long signatures are whitespace-compacted and truncated.

### `read_source` — literal code

- `{path, symbol}` — the full source of one named definition, located via
  tree-sitter node boundaries. Names are the ones shown in the outline;
  members are addressable as `Parent.child` (and bare `child`). Unknown
  symbols error with the list of available names.
- `{path, start_line, end_line}` — a 1-based inclusive line range.
- `{path}` — the whole file verbatim (bypasses the outline).

`read(path, offset, limit)` still works for ranges too (offset is 0-based),
and a small `## Reading code` system-prompt section tells the model the
outline is expected, not an error.

## When a read is outlined

A `read` is outlined only when **all** of these hold — otherwise it falls
through to the builtin read:

| Condition | Value |
|---|---|
| Tool + args | `read` with `path`, no `offset`/`limit` |
| Language | extension maps to a supported grammar (see below) |
| Grammar present | `grammars/<lang>.wasm` exists |
| File size | ≥ 120 lines and < 2 MB |
| Worth it | outline text < 50% of the file's size |
| No errors | any parse/extract failure → silent fall-through |

Thresholds live in `xi.ext.treesitter.core` (`min-lines`, `max-bytes`).

`bash` reads (`cat file`) are not outlined: the `bash` tool is hidden
whenever the clj extension is on (`:remove-tools`), which is the normal setup.

## Supported languages

| Language | Extensions | Extractor notes |
|---|---|---|
| TypeScript | `.ts .mts .cts` | imports, consts (arrow fns → fns), functions, classes, interfaces, type aliases, enums; `export` unwrapped |
| TSX | `.tsx` | same as TypeScript |
| JavaScript | `.js .jsx .mjs .cjs` | same extractor as TypeScript |
| Python | `.py .pyi` | imports, defs (decorators included in range), classes + members, module assignments |
| Rust | `.rs` | use, const/static, fns, structs + fields, enums + variants, type aliases, traits, impls, mods, macros |
| Go | `.go` | package, imports, const/var specs, type specs (structs/interfaces + members), funcs, methods |
| Nix | `.nix` | attrpath bindings across function/let/with/attrset nesting |
| Bash | `.sh .bash .zsh` | function definitions |
| CSS | `.css` | @import/@charset/@namespace, rule selectors (custom `--props` listed as children), @media/@supports + nested selectors, @keyframes, other at-rules |
| Clojure | `.clj .cljs .cljc .bb` | ns + require libspecs, def/defonce, defn (multi-arity arglists), defmethod (named `fn :dispatch`), defmacro, defprotocol/defrecord/deftype + methods, generic `def*` forms; recurses into `#?(...)` reader conditionals |

To add a language: add it to `manifest.json`, run `bb treesitter:build <lang>`,
then add an extension mapping + extractor in `src/xi/ext/treesitter/langs.cljs`.

## The parse tree

`parse-file` (Promise) and `parse-file-sync` return the tree as plain JS
objects in the shape below, so the extractors don't depend on the WASM API:

```json
{"t":"program","sr":0,"er":411,"sb":0,"eb":10184,"c":[
  {"t":"import_statement","sr":0,"er":0,"sb":0,"eb":24,"c":[…]}]}
```

- `t` node type · `sr`/`er` 0-based start/end row · `sb`/`eb` byte offsets ·
  `a: 1` anonymous node · `f` field name · `c` children.
- Node text is never in the tree — callers slice it from the source buffer via
  `sb`/`eb`. web-tree-sitter reports UTF-16 indices, so `parse` translates them
  to UTF-8 byte offsets and multi-byte text stays correct.

`parse-file-sync` exists for the rules engine's `:node` matcher, which runs in a
synchronous pipeline. It works once the runtime has loaded and returns `nil`
before that, so in the first moments after startup a `:node` rule does not
match (the same as when a grammar is missing).

## Source layout

```
resources/treesitter/        — vendored runtime + grammars (see above)
scripts/build-treesitter-wasm.mjs — rebuilds them from manifest.json
src/xi/ext/treesitter/
  parse.cljs    — loading, WASM → JSON-shaped tree, JS node accessors, text helpers
  langs.cljs    — extension → language map + per-language extractors
  skeleton.cljs — entries → outline text; symbol table for read_source
  core.cljs     — the extension: read override, read_source, system prompt, factory
```

Tests: `test/xi/ext/treesitter/` — pure formatter tests, the parse layer
(`parse_test.cljs`: all grammars load, UTF-8 offsets, sync/async parsing), and
end-to-end per-language extractor and `read`/`read_source` tests. They run
against the vendored grammars, so they don't need anything installed.
