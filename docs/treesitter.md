# Tree-sitter Outlines (`:treesitter` extension)

Context-token saving for code reads, modeled on [maki](https://maki.sh/)'s
`index` tool: reading a **large supported source file** returns a compact
structural outline (imports, type definitions, function signatures — each with
a 1-based `[start-end]` line range) instead of the full contents. The model
then pulls only the definitions it actually needs. In maki's measurements,
file reads are ~65% of all context tokens, and skeleton-first reading saves a
large fraction of that.

Two pieces:

1. **A native CLI** (`native/xi-treesitter`) — a small C program built via nix
   that dlopens nix-built tree-sitter grammar `.so` files, parses a file, and
   prints the parse tree as compact JSON. Bun shells out to it (Bun cannot
   load grammar `.so` files directly: `bun:ffi` doesn't support by-value
   structs, which `TSNode` is).
2. **An extension** (`src/xi/ext/treesitter/`) — a `:tool-gate` that
   intercepts `read` calls and returns the outline, plus a `read_source` tool
   for literal code.

The extension is a factory that returns `nil` when the CLI isn't installed, so
everything silently stays off until you run the install step.

## Install

```bash
bb treesitter:install
# = nix-build native/xi-treesitter -o ~/.config/xi/treesitter
```

This builds the CLI and a grammar bundle
(`pkgs.tree-sitter.withPlugins`) and symlinks the result at
`~/.config/xi/treesitter`:

```
~/.config/xi/treesitter/
  bin/xi-treesitter     — the parse CLI
  grammars/<lang>.so    — grammar shared objects
```

Discovery is via `$XI_TREESITTER_DIR` (default `~/.config/xi/treesitter`).
Restart the server (`bb serve:restart`) after installing — the factory checks
availability at assembly time.

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

## When the gate intercepts

A `read` is outlined only when **all** of these hold — otherwise it passes
through unchanged:

| Condition | Value |
|---|---|
| Tool + args | `read` with `path`, no `offset`/`limit` — or a plain full-file `bash` read (see below) |
| Language | extension maps to a supported grammar (see below) |
| Grammar installed | `grammars/<lang>.so` exists |
| File size | ≥ 120 lines and < 2 MB |
| Worth it | outline text < 50% of the file's size |
| No errors | any parse/extract failure → silent pass-through |

Thresholds live in `xi.ext.treesitter.core` (`min-lines`, `max-bytes`).

### The bash bypass

Without it, `cat file` via the `bash` tool would be a trivial bypass of the
read gate (Spotify's shunt plugin guards the same hole with its
`check-bash-read` hook). The gate also outlines a `bash` call when its
command is a **plain full-content read of a single file**:

- `cat` / `less` / `more` with exactly one file argument, or
- `head` / `tail` requesting ≥ `min-lines` lines (`-n N`, `-nN`, `-N`,
  `--lines=N`).

Anything targeted or composed passes through untouched: pipes
(`cat f | grep x`), redirects, quoting, globs, multiple files, unknown flags
(`tail -f`), or a small `head -n 20`. The same file-qualification rules as
`read` apply (supported language, size, worth-it check).

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

To add a language: add the grammar to `native/xi-treesitter/default.nix`,
re-run `bb treesitter:install`, then add an extension mapping + extractor in
`src/xi/ext/treesitter/langs.cljs`.

## The CLI

```
xi-treesitter <grammar-dir> <lang> <file>
```

dlopens `<grammar-dir>/<lang>.so`, resolves `tree_sitter_<lang>`, parses
`<file>`, and prints the tree as JSON on stdout:

```json
{"t":"program","sr":0,"er":411,"sb":0,"eb":10184,"c":[
  {"t":"import_statement","sr":0,"er":0,"sb":0,"eb":24,"c":[…]}]}
```

- `t` node type · `sr`/`er` 0-based start/end row · `sb`/`eb` byte offsets ·
  `a: 1` anonymous node · `f` field name · `c` children.
- Node text is never in the JSON — callers slice it from the source buffer via
  `sb`/`eb` (byte offsets, so multi-byte UTF-8 stays correct).
- Exit codes: 2 usage · 3 grammar load · 4 file read · 5 ABI mismatch ·
  6 parse failure.

Performance: ~40 ms for a 4000-line file (2.6 MB JSON), well under
interactive-read latency.

## Source layout

```
native/xi-treesitter/
  main.c        — the CLI (TSTreeCursor walk → JSON)
  default.nix   — build: CLI + tree-sitter.withPlugins grammar bundle
src/xi/ext/treesitter/
  parse.cljs    — CLI discovery/spawn, JS node accessors, text helpers
  langs.cljs    — extension → language map + per-language extractors
  skeleton.cljs — entries → outline text; symbol table for read_source
  core.cljs     — the extension: tool-gate, read_source, system prompt, factory
```

Tests: `test/xi/ext/treesitter/` — pure formatter tests plus end-to-end
per-language extractor tests and gate/`read_source` tests that spawn the real
CLI (skipped when it isn't installed).
