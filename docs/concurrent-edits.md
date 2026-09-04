# Concurrent edits & robust file editing

Design doc for making file edits safe when **multiple sub-agents edit the same
file**, and cheaper/more reliable in general.

## The problem

xi's edit tool (`src/xi/tools/edit.cljs`) is a classic **`str_replace`**:
`content.indexOf(oldText)`, error on zero or multiple matches, write. It is
**stateless** — nothing ties an edit to the file version the agent actually
read. With concurrent sub-agents (each may be a separate process editing the
same file on disk) this produces two failure modes:

1. **Lost/retry loop** — agent B edits first, agent A's `oldText` no longer
   matches → *"Could not find the exact text"* → the model burns tokens
   regenerating the edit.
2. **Silent corruption** — `oldText` still matches, but now at a stale or
   duplicated location, so A's change lands in the wrong place with no error.

There is no freshness check, so A never learns the file moved underneath it.

## Prior art (what we investigated)

| Approach | Addressing | Concurrency behavior |
|---|---|---|
| xi today — `str_replace` | exact text | survives line movement, but stale/dup context applies silently; no staleness signal |
| smartedit `apply` ([theduke/smartedit](https://github.com/theduke/smartedit)) | **line numbers** (`file:start-end`) | **worst case** — any insert above a target shifts every line number below it, silently. Its AST half (`ast-print`) is *read-only* and xi already has it. |
| hashline / "The Harness Problem" ([blog](https://stencil.so/blog/the-harness-problem)) | `{line}:{hash}\|` content anchors | edit references anchors; on file change the hash mismatches → **edit rejected before write**. Stateless variant works across processes. |
| Dirac hash-anchors ([blog](https://dirac.run/posts/hash-anchors-myers-diff-single-token)) | single-token word anchors + Myers reconciler | as above + re-anchors only changed lines; **stateful** (per-process anchor map) — awkward across xi's multi-process sub-agents |
| Dirac `edit_ast` / clj-surgeon `clj_replace` | **AST symbol** | address is stable across unrelated edits; two agents on *different* symbols never collide, same symbol → clean detectable conflict |

Key takeaways:

- **Line-number addressing (smartedit `apply`) is a step backward** for the
  concurrency problem — do not adopt it. Its value (AST-for-reading + targeted
  edits) is already covered by xi's treesitter extension + `read_source`.
- **Symbol-addressed (AST) editing is the ideal** answer because it partitions
  a file by semantic unit. xi **already has it for Clojure** via clj-surgeon
  (`clj_replace`, `clj_mv`, `clj_extract`, …), matched structurally, ignoring
  whitespace.
- For non-Clojure files, a **stateless content-hash (hashline)** edit gives
  cross-process staleness rejection *now*, without building a per-language
  rewriter.

## Design for xi — two-tier, staged

### Part A — freshness guard on `edit` (minimal, ship first)

Smallest change that kills *silent corruption*. Keep `str_replace`; add an
optional freshness token.

- On `read` (and `grep`/`read_source`), the tool result gains a small,
  content-derived stamp for the file — e.g. a short hash of the full file
  contents (`sha1` truncated) plus `mtime`/size as a fast pre-check.
- `edit` accepts an optional `expected_hash` (or `read_hash`). At write time
  the tool re-reads the file, recomputes the hash, and if it differs from
  `expected_hash`, **rejects** with a message telling the model the file
  changed since its last read and to re-read.
- Optional (not required) → the guard is advisory when omitted, so existing
  behavior and other tools keep working. We can later make it required for
  sub-agent contexts.

Touch points: `src/xi/tools/read.cljs`, `src/xi/tools/edit.cljs`,
`src/xi/tools/grep.cljs`, a shared hash helper in `src/xi/tools/util.cljs`.

**Result:** concurrent edits can no longer silently misapply — a stale edit is
rejected with a clear "re-read" instruction.

### Part B — stateless hashline edit for non-Clojure files

Removes the retry-loop token waste *and* gives per-line staleness detection.

- **On read**, tag lines: `{line}:{hash}|code` (2–3 char content hash), e.g.
  `2:f1|  return "world";`. Stateless — the hash is a pure function of the
  line's content, so any process computes the same value (no shared state,
  fits xi's multi-process sub-agents).
- **New edit path** — an anchor-based operation: `replace <start-anchor>
  through <end-anchor> with <replacement>`, `insert after <anchor>`,
  `delete <start> through <end>`. The tool validates each anchor by
  re-hashing the referenced line at write time; mismatch → reject (the file
  moved).
- Output cost drops from `O(search + replace)` to `O(replace)` — the model no
  longer reproduces `oldText`.
- Keep `str_replace` as a fallback path (some edits are easier expressed as
  text).

Integration mirrors the treesitter extension's `:tool-gate` pattern: line
tagging can be added to the `read` result; the anchor edit can be a new tool or
a new mode of `edit`. Decide during implementation whether this is core
(`src/xi/tools/`) or an extension (`src/xi/ext/`) — leaning core since it
replaces the base edit affordance.

We adopt the **stateless** hashline (Harness Problem), **not** Dirac's stateful
word-anchor reconciler — statelessness is what makes it correct across
independent sub-agent processes.

### Part C — steer Clojure edits to clj-surgeon

For `.clj/.cljs/.cljc`, xi already has true AST editing (clj-surgeon
`clj_replace` et al.). This is inherently concurrency-robust: it addresses a
named form / structural pattern, so unrelated edits elsewhere don't disturb it,
and two agents editing the same form get a clean conflict.

- Bias the agent (system prompt / tool descriptions / a `:tool-gate` hint) to
  **prefer `clj_replace` over `edit` for Clojure files**.
- No new machinery — this is guidance + possibly a gentle nudge when `edit` is
  called on a `.clj*` path.

## Rollout order

1. **Part A** — freshness guard. Small, high-value, low-risk. One commit.
2. **Part C** — steer Clojure edits to clj-surgeon. Docs + prompt/gate nudge.
3. **Part B** — stateless hashline for non-Clojure. Larger; do after A proves
   the freshness plumbing.

## Open questions

- Hash function & length for the file-level stamp (Part A) vs. per-line tag
  (Part B) — collision risk vs. token cost. Harness Problem used 2–3 chars
  per line.
- Should the freshness token become **required** when running under a
  sub-agent, while staying optional for the top-level agent?
- Where the per-line tagging lives so it doesn't fight the treesitter
  extension's outline-on-read (outline replaces content for *large* files;
  hashline tagging applies to the literal-content reads / `read_source`).
- Whether Part B is core or an extension.

## References

- The Harness Problem (hashline benchmark): <https://stencil.so/blog/the-harness-problem>
- Dirac hash-anchors + Myers diff: <https://dirac.run/posts/hash-anchors-myers-diff-single-token>
- smartedit (AST reading; line-based editing): <https://github.com/theduke/smartedit>
- maki (index/skeleton reads): <https://maki.sh/>
