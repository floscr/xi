# Session History Navigation (/tree)

`/tree` opens an interactive selector over the room's chat history,
letting you jump back to an earlier point in the conversation and take it
in a new direction.

## How it works

The selector works directly from room `:history` in app state — there is
no parallel data structure to keep in sync.

```
 /tree  →  [:ui :tree-open?] = true
      │
      ▼
 history selector (xi.tui.history-selector)
 over room :history entries
      │
      ├─ Enter        → :tree/navigate {:index i :mode :navigate}
      └─ Ctrl-Enter   → :tree/navigate {:index i :mode :edit
                                        :editor-text <user message>}
      │
      ▼
 tree-navigate (xi.commands):
   • truncate room :history to the chosen index
     (:edit — exclusive: drop the message being edited;
      :navigate on a user message — inclusive +1: keep the response)
   • clear [:session :provider-session-id]
   • flag [:session :inject-history?]
   • clear busy/queued, close the selector
      │
      ▼
 next prompt starts a FRESH provider session with the
 truncated conversation injected into the system prompt
 (xi.agent/history->context renders user/assistant text
  exchanges as a <conversation_history> block)
```

Because everything is pure state, the truncation mirrors to all connected
clients like any other event, and nothing on disk is modified — the
provider's old session JSONL stays intact; navigation just detaches from
it.

### Context carry-over

The fresh provider session knows nothing about the conversation, so
`start-turn-effect` (xi.agent) checks the session flags: when
`:inject-history?` is set and there is no `provider-session-id` to
resume, it renders the truncated history (user prompts and assistant
text; thinking and tool calls are skipped) and appends it to the base
system prompt. Once a turn completes and a new provider session id
lands, resume takes over and injection stops — no flag bookkeeping
needed. `/clear` and `/new` rebuild the session map, so the flag
vanishes naturally.

## Selector UI

- **`/tree`** opens the selector (TUI bottom panel)
- **Arrow up/down** — move selection
- **Type** — search/filter entries; Backspace/Ctrl-U edit the query
- **Tab** — cycle kind filters: User → User & Agent → All
- **Enter** — navigate to that point
- **Ctrl-Enter** (on a user message) — fork + edit: history is truncated
  *before* the message and its text is prefilled into the editor
- **Esc** — close without navigating

## Files

| File | Role |
|------|------|
| `src/xi/commands.cljs` | `/tree` command, `:tree/navigate`, `:tree/close` handlers |
| `src/xi/tui/history_selector.cljs` | Interactive TUI selector (filtering, search) |
| `src/xi/client/tui.cljs` | Panel wiring (dialog > menu > tree > editor priority) |

## History: the old branching tree

Before the rebuild, `/tree` kept a parallel append-only tree
(`.tree.jsonl`, `id`/`parentId` pointers) that preserved abandoned branches.
That unused code (`session/tree.cljs`, `session/tree_recorder.cljs`,
`tui/tree_selector.cljs`) was deleted; if branch preservation is wanted
again, recover it from git: `git log --diff-filter=D -- src/xi/session/tree.cljs`.
