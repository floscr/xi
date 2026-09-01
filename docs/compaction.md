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
      │  (:save-current? persists the outgoing session,
      │   :truncated-from links the new one back to it,
      │   :keep-history? keeps the old conversation visible)
      │
      └─ failure → :compact/failed (busy cleared, error status)
```

## The pre-truncation conversation stays visible

Truncation only limits what the *model* sees — the user keeps the full
conversation on screen:

- **Live**: `:session/created` with `:keep-history?` keeps the room's
  existing history above a divider status line instead of clearing it. Every
  carried-over entry is flagged `:no-llm? true`.
- **On resume**: the new session persists a `:truncated-from` lineage link
  (the previous Xi session id). `xi.session/read-session-messages` follows
  the chain and prepends each ancestor's transcript — blocks tagged
  `:pre-truncation? true`, separated by a `{:type :truncation-divider}`
  block — which `xi.commands/messages->history` turns into `:no-llm?`
  history entries plus the same divider line.
- **Never sent to the model**: `xi.agent/history->context` (fresh-session
  context injection after /tree or a failed resume) and
  `xi.agent/history->messages` (sessionless providers' transcript replay)
  both skip `:no-llm?` entries; Claude resumes its own provider session,
  which never contained them. Session titling (`first-user-text`) skips
  them too.

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
