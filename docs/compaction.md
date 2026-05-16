# Context Compaction

Xi supports in-session context compaction via `/compact`. This summarizes the current conversation into a condensed form and starts a fresh session with the summary injected, preserving continuity without the full token cost.

## Usage

```
/compact              Summarize the entire conversation
/compact auth flow    Summarize with focus on "auth flow"
```

The optional focus argument guides the summarizer to emphasize specific topics.

## How It Works

```
 /compact [focus]
      │
      ▼
 Resume current SDK session
      │
      ▼
 Claude Sonnet summarizes the conversation
 (preserves: file paths, decisions, task state, errors)
      │
      ▼
 Clear session + event history
      │
      ▼
 Dispatch summary into new session as first turn
 (wrapped in <conversation-summary> tags)
```

1. The runtime emits `:compact-start` (TUI/web show a spinner)
2. `compaction/summarize` resumes the current Claude SDK session and asks Sonnet to produce a summary
3. The current session is cleared (`session/create-session`, `provider/clear-session!`)
4. Event history is reset
5. The summary is dispatched as a user message into the fresh session
6. The model acknowledges the summary and waits for the next instruction

## Summary Prompt

The summarizer preserves:
- All file paths read, written, or edited
- Key decisions and their rationale
- Current task state (done vs pending)
- Errors encountered and resolutions
- Important context needed to continue

## Implementation

- **Source:** `xi.compaction` — SDK-based summarization
- **Command:** registered as `/compact` in `runtime/commands.cljs`
- **Model:** always uses `claude-sonnet-4-20250514` for cost efficiency
- **Events:** `:compact-start`, `:session-compacted`, `:command-error`
