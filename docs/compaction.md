# Context Compaction

Xi supports in-session context compaction via `/truncate`. This summarizes
the current conversation into a condensed form and starts a fresh session
with the summary injected, preserving continuity without the full token
cost.

## Usage

```
/truncate              Summarize the entire conversation
/truncate auth flow    Summarize with focus on "auth flow"
```

The optional focus argument guides the summarizer to emphasize specific topics.

## How it works

```
 /truncate [focus]
      │  :compact/request  (refused while busy / no provider session)
      ▼
 busy? = true, status "Compacting conversation..."
 [:compact/start] effect
      │
      ▼
 Summary turn through the claude provider,
 resuming the room's provider session
 (model: claude-sonnet-4, COMPACT_MODEL)
      │
      ├─ success → :compact/done {:summary …}
      │     │
      │     ▼
      │  [:session/new] effect with :after-prompt —
      │  fresh session, summary dispatched as the first
      │  user message wrapped in <conversation-summary>
      │
      └─ failure → :compact/failed (busy cleared, error status)
```

Everything follows the standard handler/effect split
([architecture.md](architecture.md)):

- **Pure handlers** (`xi.compaction/handlers`): `:compact/request`,
  `:compact/done`, `:compact/failed`.
- **Effects** (`create-fx`): `:compact/start` runs the summary turn via the
  claude provider's `:start-turn!`, collecting text deltas; `:compact/abort`
  cancels the in-flight handle.
- **Abort chaining**: `abort-handler` is chained onto `:agent/abort` at
  assembly, so Escape also stops an in-flight compaction.

## Summary prompt

The summarizer preserves:
- All file paths read, written, or edited
- Key decisions and their rationale
- Current task state (done vs pending)
- Errors encountered and resolutions
- Important context needed to continue

## Implementation

- **Source:** `src/xi/compaction.cljs`
- **Command:** `/truncate` in `xi.commands/built-in-commands` dispatches
  `:compact/request`
- **Model:** always `claude-sonnet-4-20250514` (`COMPACT_MODEL`) for cost
  efficiency
- **Assembly:** `xi.cli` merges `compaction/handlers` and chains
  `compaction/abort-handler` onto `:agent/abort`; `create-fx` receives the
  provider map
