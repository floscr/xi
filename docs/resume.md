# Resume tools — /trim, /rollover, /lineage

Session-continuity tools ported from
[claude-code-tools' `aichat resume`](https://pchalasani.github.io/claude-code-tools/tools/aichat/resume/),
implemented as the `xi.ext.resume` server extension. Compaction (`/compact`)
reduces the active context to a lossy summary; these tools instead keep every
detail **recoverable on demand** — trimmed text and ancestor sessions are cited
by file path, and the agent pulls them back with its normal `read`/`grep` tools
only when needed.

Because xi starts a fresh SDK query with `--resume <id>` on **every turn**, a
trim of the transcript takes effect on the very next turn — no quit/resume
dance like in plain Claude Code.

## /trim — trim the transcript in place

Truncates bloated tool results (and optionally long assistant messages) out of
the current session's Claude CLI transcript
(`~/.claude/projects/<cwd>/<session>.jsonl`), keeping the same session id.

```
/trim                 # preview with defaults — changes nothing
/trim yes             # apply the pending preview
/trim cancel          # abandon the pending preview
/trim help            # usage
```

Options are shape-based and order-free (any mix, any order):

| Shape | Meaning |
|---|---|
| a number | char threshold — only content longer than this is trimmed (default 500) |
| `tool,names` | only trim these tools' results (default: all tools) |
| `-N` | also trim long **assistant** messages, keeping the last N |
| `+N` | also trim the first N long assistant messages |

```
/trim 800             # only content > 800 chars
/trim bash,read       # only bash/read results
/trim -20 800 bash    # combine — any order
```

The loop is **preview → `/trim yes`** (a preview expires after 10 minutes).
Applying:

- writes a timestamped backup next to the transcript
  (`<id>.pre-trim-<ts>.jsonl.bak`),
- replaces each trimmed text with a placeholder citing the backup file and
  line (`[trimmed by xi /trim] tool result (bash), 45300 chars removed —
  original at …/<id>.pre-trim-….jsonl.bak line 42`),
- keeps the same session id — the next turn resumes the lean transcript.

The room's visible history is untouched (it lives in room state, not the
transcript) — only the model's context shrinks. Trimming is blocked while the
agent is busy. Without `-N`/`+N`, assistant messages are never touched.

## /rollover — fresh session with lineage pointers

Starts a fresh session (like `/compact`) whose first message carries a
`<session-lineage>` block: a chronological list of all ancestor sessions with
their transcript paths, so the agent can recover *any* prior detail on demand
instead of relying on a summary.

```
/rollover                       # quick — lineage pointers only, no AI turn
/rollover the auth refactor     # + an AI work summary focused on the argument
```

With a focus argument, a summary turn runs first (same mechanism as
`/compact`, resuming the old session with a summarization prompt; abortable
with Escape) and the summary is injected alongside the lineage block.

Lineage is chained through the same `:truncated-from` link `/compact` writes
into the session metadata, so `/compact` and `/rollover` generations mix in
one chain. The new session's prior conversation stays visible above the
truncation divider, exactly like `/compact`.

## /lineage — inspect the chain

Prints the current session's ancestor chain (oldest first) with each
generation's transcript path.

## Agent-side contract

The extension appends a short system-prompt note telling the agent what
`<session-lineage>` blocks and `[trimmed by xi /trim]` placeholders mean: the
cited JSONL files hold the full original text, to be consulted with
`read`/`grep` only when earlier detail is actually needed. No dedicated
recovery tool is required.

## Not ported

aichat's **Clone** and **Smart trim** (AI decides what to cut) strategies, and
its standalone CLI (`aichat trim-in-place` etc.) — `/trim` + `/rollover`
cover the workflow inside xi.

## State

Room-scoped `[:rooms rid :ext :resume]` holds `{:pending {:opts … :at …}}`
for the trim preview. Extension id `:resume`, registered in the `server`
vector of `src/xi/config.cljc`; the factory takes `:providers` from the server
ctx for the focus-summary turn.
