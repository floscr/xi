# Session Tree

Xi maintains its own append-only conversation tree alongside the Claude session. This enables branching — navigating to an earlier point in the conversation and taking it in a new direction — without modifying or losing any history.

## Why a separate tree?

Claude Code (via the SDK) stores its conversation in a linear JSONL file. Xi can resume that session with `resume-session-id`, but the SDK has no concept of branching or forking. The session tree is Xi's layer on top: it records the same conversation events in a tree structure with `id`/`parentId` pointers, so any node can become the starting point for a new branch.

## Data model

The tree is stored as a JSONL file (`.tree.jsonl`) next to the session's `.json` metadata file:

```
~/.config/xi/sessions/{cwd}/{session-id}.json          ← session metadata
~/.config/xi/sessions/{cwd}/{session-id}.tree.jsonl     ← session tree
```

The first line is a header:

```json
{"type":"session","id":"<session-id>","version":1,"cwd":"/path/to/project","timestamp":"..."}
```

Every subsequent line is a tree entry with these common fields:

| Field | Description |
|-------|-------------|
| `id` | Short random hex ID (8 chars) |
| `parentId` | Parent entry's ID, or `null` for roots |
| `timestamp` | ISO 8601 timestamp |
| `type` | Entry type (see below) |

### Entry types

| Type | Fields | Description |
|------|--------|-------------|
| `user-message` | `text`, `image-count?` | User prompt |
| `assistant-text` | `text` | Assistant response text (flushed on tool-start or turn-end) |
| `tool-use` | `name`, `tool-call-id`, `arguments` | Tool call proposed by assistant |
| `tool-result` | `tool-name`, `content`, `is-error` | Tool execution result (truncated to 2KB) |
| `turn-end` | `usage`, `cost` | End of agent turn with token/cost stats |
| `model-change` | `model` | Model switch within session |
| `compaction` | `summary` | Context compaction occurred |

The tree has a **leaf pointer** — the current position. `append!` creates a child of the leaf and advances it. `branch!` moves the leaf to an earlier entry without modifying anything. The next `append!` then forks the tree.

## How navigation works (bridge interaction)

When the user navigates to a different point in the tree, the following happens:

```
┌─────────────────────────────────────────────────────────────┐
│ 1. Branch the Xi tree                                       │
│    tree/branch! moves the leaf pointer to the target node   │
│    (or tree/reset-leaf! if navigating before root)          │
│    No entries are deleted or modified                        │
├─────────────────────────────────────────────────────────────┤
│ 2. Build branch context                                     │
│    tree/build-message-context walks root→leaf, collecting   │
│    user-message + assistant-text entries as message pairs    │
│    This becomes the conversation history for the new branch │
├─────────────────────────────────────────────────────────────┤
│ 3. Clear the Claude session                                 │
│    cli-session-id is set to nil                              │
│    provider/clear-session! resets the SDK session state      │
│    The next turn will NOT resume the old Claude JSONL        │
├─────────────────────────────────────────────────────────────┤
│ 4. Inject history into system prompt                        │
│    On the next turn, branch context is appended to the      │
│    system prompt as <conversation_history>...</>             │
│    This is consumed once (cleared after first use)           │
├─────────────────────────────────────────────────────────────┤
│ 5. New Claude session                                       │
│    The SDK starts a fresh session — new JSONL file           │
│    The model sees prior conversation as system context       │
│    The user's message appears as the first real user turn    │
└─────────────────────────────────────────────────────────────┘
```

The key insight: **navigation starts a fresh Claude session**. The old Claude JSONL is not truncated or modified. Instead, the conversation history up to the fork point is injected into the system prompt so the model has context for the new branch.

This approach avoids mutating Claude's session files and sidesteps SDK limitations around mid-conversation forking.

### Bridge-level detail

Xi talks to Claude Code through the SDK's MCP bridge (see [mcp-tool-bridge.md](mcp-tool-bridge.md) for full architecture). The relevant bridge interactions during navigation:

1. **`provider/clear-session!`** — resets the in-memory session state atom. The SDK's `query()` function will no longer receive a `resume` session ID.

2. **`resume-session-id`** — normally passed to the SDK's `query()` options to continue an existing Claude conversation. After navigation, this is `nil`, forcing a new session.

3. **System prompt injection** — the SDK's `query()` accepts a `systemPrompt` option. Xi appends the branch context here (once), giving the model full conversation history without needing the old JSONL.

4. **New `cli-session-id`** — the SDK returns a new session ID from the first turn on the new branch. Xi stores this for subsequent turns on this branch.

## Recording

The tree recorder (`session/tree_recorder.cljs`) subscribes to the runtime event bus and maps events to tree entries:

| Runtime event | Tree entry |
|---------------|------------|
| `:user-message` | `user-message` |
| `:turn-start` | (starts text accumulation) |
| `:text-delta` | (accumulated into buffer) |
| `:tool-start` | Flushes accumulated text → `assistant-text`, then `tool-use` |
| `:tool-result` | `tool-result` |
| `:turn-end` | Flushes remaining text → `assistant-text`, then `turn-end` |
| `:model-changed` | `model-change` |
| `:session-compacted` | `compaction` |

Text deltas are buffered and flushed as a single `assistant-text` entry when a tool call starts or the turn ends. This avoids per-token entries.

The recorder handles session lifecycle:
- `:session-cleared` → creates a fresh tree for the new session
- `:session-resumed` → loads the existing `.tree.jsonl` or creates a new one

## TUI interaction

- **ESC** (when editor is empty and not busy) → opens the tree selector
- **/tree** command → opens the tree selector
- The selector shows all entries as a flat list with indentation for depth
- Active branch path is marked with `●`
- Filter modes (cycle with Tab): All → User messages → Assistant → Tools
- Type to search / filter entries
- Enter on a `user-message` → text goes to the editor, chat re-renders from the branch point
- Enter on other entry types → navigates to that point, chat re-renders

## Files

| File | Role |
|------|------|
| `src/xi/session/tree.cljs` | Core tree data structure: create, append!, branch!, get-tree, persistence |
| `src/xi/session/tree_recorder.cljs` | Maps runtime bus events → tree entries |
| `src/xi/runtime.cljs` | Creates tree + recorder, wires bus subscription, exposes `navigate-tree!` |
| `src/xi/session.cljs` | `tree-filepath` — derives `.tree.jsonl` path from session |
| `src/xi/tui/tree_selector.cljs` | Interactive TUI selector with filter modes and search |
| `src/xi/client/tui.cljs` | ESC/`/tree` wiring, `:tree-navigated` event rendering |
| `test/xi/session/tree_test.cljs` | Tree data structure tests |
| `test/xi/session/tree_recorder_test.cljs` | Recorder event mapping tests |

## Safety

- **Existing session files are never modified.** The tree is a new sibling file.
- **Claude JSONL files are never modified.** Navigation starts a fresh session, not a truncated one.
- **Append-only.** Entries are never deleted or overwritten. `branch!` only moves a pointer.
- **Worst case:** extra `.tree.jsonl` files that can be safely deleted.
