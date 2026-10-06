# How the agent reads code

When the agent reads a large source file, Xi parses it into a syntax tree and
hands over an outline of that tree: imports, types and function signatures,
each with its line range. The agent then reads only the definitions it needs,
so a 2,000-line file costs a few dozen lines of context instead of the whole
file.

## What the agent sees

For a `read` of a large source file, the result is the outline instead of the
contents:

```text
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

Classes, interfaces, structs and similar entries list their members one level
down, up to 8 each, then a `[N more truncated]` line. Long signatures are
shortened.

The agent gets the actual code only when it asks for it, with `read_source`:

| Call | Returns |
| --- | --- |
| `read_source(path, symbol)` | The full source of one definition, named as in the outline. A member is `Parent.child` (or just `child`). An unknown name fails with the list of names in the file. |
| `read_source(path, start_line, end_line)` | A 1-based, inclusive line range. |
| `read(path, offset, limit)` | A line range too; `offset` counts from 0. |
| `read_source(path)` | The whole file, outline skipped. |

The agent is told the outline is expected, so it does not treat it as an
error. You do not have to do anything; a typical read of a big file turns into
an outline followed by one or two `read_source` calls.

## When a read is outlined

A read becomes an outline only when all of these hold. Otherwise the agent
gets the file as usual.

| Condition | Value |
| --- | --- |
| The call | `read` with a path and no `offset` or `limit`. |
| The language | The file extension is in the table below. |
| The size | At least 120 lines and under 2 MB. |
| The saving | The outline is less than half the size of the file. |
| The parse | It succeeds. Any parse error falls back to the plain read without a message. |

Small files, config files, Markdown and plain text are read in full. Shell
commands such as `cat file` are never outlined.

## Languages

| Language | Extensions |
| --- | --- |
| TypeScript, TSX | `.ts` `.mts` `.cts` `.tsx` |
| JavaScript | `.js` `.jsx` `.mjs` `.cjs` |
| Python | `.py` `.pyi` |
| Rust | `.rs` |
| Go | `.go` |
| Clojure, ClojureScript | `.clj` `.cljs` `.cljc` `.bb` |
| Nix | `.nix` |
| Bash | `.sh` `.bash` `.zsh` |
| CSS | `.css` |

Parsing runs inside Xi on WebAssembly grammars that ship with it. There is
nothing to install, and no native binary. If the grammars cannot be found,
every read stays plain; see `XI_TREESITTER_DIR` in
[Configuration](configuration.md#environment-variables).

## Where else Xi uses the tree

[Permission rules](rules-reference.md) can match on the syntax tree of the
code an `edit` touches or a `write` creates, with the `:node` field. That
uses the same grammars.
