# Syntax Highlighting

Xi has a built-in syntax highlighter with zero npm dependencies. It supports 269 languages via grammars ported from [chroma](https://github.com/alecthomas/chroma) (MIT licensed).

## How It Works

The highlighter is a regex-walking tokenizer. For each line of source code, it tries regex rules in order at each character position — first match wins, emitting a typed token. Unmatched characters become plain `:text` tokens.

```
Source: (defn foo [x] 42)
         │         │   │
         ▼         ▼   ▼
  :keyword-decl  :name-var  :number
```

### Pipeline

```
source string
  → tokenize (regex rules → token stream)
  → merge-adjacent (coalesce same-type runs)
  → colorize (token type → ANSI escape codes)
  → rendered string with embedded colors
```

### Architecture

```
highlight/
  core.cljc       Tokenizer engine — ~50 lines, the whole runtime
  grammars.cljs   Lazy-loading registry + grammar loader (node, via fs)
  bundle.cljc     Compile-time inlined grammars for the browser (~29 common
                  languages, ~35KB) — no filesystem access needed
  theme.cljc      Token type → ANSI true-color mapping (Nord-inspired, TUI)
  theme_css.cljc  Token type → CSS class mapping (web client)

resources/highlight/grammars/
  registry.edn   Alias → filename mapping (722 entries)
  clojure.edn    Grammar rules for Clojure
  python.edn     Grammar rules for Python
  ...            269 grammar EDN files total
```

### Lazy Loading (node)

In the node build (TUI/server), grammars are **not** bundled into the compiled JS output. Instead, they live as individual EDN files in `resources/highlight/grammars/` and are loaded on demand:

1. `get-grammar "clojure"` looks up `"clojure"` in `registry.edn` → filename `"clojure"`
2. Reads `resources/highlight/grammars/clojure.edn` from disk via `node:fs`
3. Parses the EDN with `cljs.reader/read-string`
4. Caches the result in an atom — subsequent calls for the same grammar are instant

This keeps the release build lean (only the tokenizer engine + registry loader are compiled) while supporting 269 languages. Each grammar EDN file is typically 2–15KB.

### Bundled grammars (browser)

The web client can't read grammar files from disk, so `bundle.cljc` inlines a curated set of ~29 common languages (bash, clojure, python, typescript, rust, …) at compile time via macros. `bundle/get-grammar` is the browser-side equivalent of `grammars/get-grammar`; unknown languages render unhighlighted. The web theme maps token types to CSS classes (`theme_css.cljc`, `hl-*` classes styled in `resources/public/css/style.css`) instead of ANSI codes.

## Where It's Used

| Location | What gets highlighted |
|---|---|
| **Markdown code blocks** | ` ```clojure ... ``` ` in assistant messages |
| **Tool output (live)** | `read`, `write`, `edit` results — by file extension |
| **Tool output (history)** | Same tools when replaying sessions via `/resume` |
| **Diff views** | `edit` output gets green/red bg for added/removed lines |
| **Git diffs** | `git_file_diff`, `git_hunk` — highlighted by file extension |
| **Web client** | markdown code blocks in chat, via bundled grammars + CSS classes |

### Diff Highlighting

Edit tool output produces unified diffs. The highlighter detects these and:

1. Strips the `+ `/`- `/`  ` prefix
2. Syntax-highlights the actual code
3. Applies a tinted background: green (`rgb(35,60,45)`) for additions, red (`rgb(65,40,42)`) for removals
4. Re-applies the diff bg after each syntax color reset so the background persists across the line

The bg colors are blended with the tool block bg (`rgb(38,44,55)`) for a subtle tint rather than harsh full-color bands.

## Grammars

### Origin

Grammars are ported from [chroma](https://github.com/alecthomas/chroma), a Go syntax highlighter based on [Pygments](https://pygments.org/). Chroma defines grammars as XML files with regex rules and token types organized into states. Our converter extracts the `root` state rules, inlines `<include>` directives, and outputs flat EDN data.

### Format

Each grammar EDN file is a vector of `{:pattern :token}` maps — tried in order, first match wins:

```clojure
[{:pattern ";.*$"                  :token :comment}
 {:pattern "[,\\s]+"               :token :text}
 {:pattern "-?\\d+\\.\\d+"         :token :number}
 {:pattern "\"(?:\\\\.|[^\"])*\""  :token :string}
 {:pattern "::?[\\w!$%*+<=>?/.#-]+" :token :string-symbol}
 {:pattern "(?<=\\()defn(?=\\s)"   :token :keyword-decl}
 ...]
```

### Token Types

Mapped from chroma/Pygments conventions:

| Token | Meaning | Color |
|---|---|---|
| `:comment` | Comments | Muted gray-blue |
| `:string` | String literals | Green |
| `:string-symbol` | Symbols, Clojure keywords | Purple |
| `:number` | Numeric literals | Purple |
| `:keyword` | Language keywords | Blue |
| `:keyword-decl` | Declaration keywords (`defn`, `class`) | Blue |
| `:name-builtin` | Built-in functions | Teal |
| `:name-fn` | Function names | Teal |
| `:name-var` | Variables, identifiers | Light gray |
| `:operator` | Operators | Blue |
| `:punctuation` | Brackets, braces, etc. | Light gray |
| `:text` | Whitespace, unmatched | Inherited (no color) |

### Limitations

- **Single-state only**: Grammars that rely entirely on multi-state push/pop transitions (C#, Common Lisp, Emacs Lisp, Groovy, Standard ML, etc.) are not supported — their root state had no usable rules after filtering.
- **No multi-line tokens**: Each line is tokenized independently. Multi-line strings or block comments won't highlight correctly across line boundaries.
- **Best-effort**: Some complex grammars lose fidelity when flattened to root-state-only rules. The highlighting is "good enough" for a TUI, not editor-grade.

## Updating Grammars

To sync with upstream chroma or regenerate after modifying the converter:

```bash
bun scripts/convert-chroma-grammars.mjs
```

The script:
1. Fetches all XML grammar files from `github.com/alecthomas/chroma/lexers/embedded/`
2. Parses each XML, extracts `root` state rules with inlined includes
3. Filters out rules using push/pop, bygroups, or lexer delegation (unsupported)
4. Converts Python-style named groups `(?P<name>...)` to JS `(?<name>...)`
5. Tests each regex for JS compatibility, skips incompatible ones
6. Deduplicates names and registry aliases
7. Writes individual EDN files to `resources/highlight/grammars/` + a `registry.edn`

Custom aliases (e.g. `cljs`, `cljc` → clojure) are added automatically by the converter.

## Adding a New Grammar

### Option 1: Wait for chroma

If the language exists in chroma, just regenerate. New languages upstream will be picked up automatically.

### Option 2: Write one by hand

Create an EDN file in `resources/highlight/grammars/my_lang.edn`:

```clojure
[{:pattern "#.*$"           :token :comment}
 {:pattern "\"[^\"]*\""     :token :string}
 {:pattern "\\b\\d+\\b"     :token :number}
 {:pattern "\\b(?:if|else|fn)\\b" :token :keyword}
 {:pattern "[{}()\\[\\]]"   :token :punctuation}
 {:pattern "\\w+"           :token :name-var}
 {:pattern "\\s+"           :token :text}]
```

Then add entries to `resources/highlight/grammars/registry.edn`:

```clojure
"my-lang" "my_lang"
"ml"      "my_lang"
```

Rules are tried in order — put specific patterns (keywords, builtins) before general ones (identifiers). Use `"y"` (sticky) regex semantics: patterns match at the current position only.

## Changing the Theme

Edit `highlight/theme.cljc`. The `colors` map is token type → ANSI true-color escape code. Set a value to `nil` to inherit the default foreground.

```clojure
(def ^:private colors
  {:comment  "\033[38;2;106;115;141m"   ;; rgb(106,115,141)
   :string   "\033[38;2;163;190;140m"   ;; rgb(163,190,140)
   :keyword  "\033[38;2;129;161;193m"   ;; rgb(129,161,193)
   ...})
```

The current palette is Nord-inspired. To use a different theme, replace the RGB values. All colors use 24-bit true-color (`\033[38;2;R;G;Bm`).
