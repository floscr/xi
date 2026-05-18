# Session Tree View

## Overview
Add Pi-style tree view to Xi. Press ESC when prompt is empty → view conversation tree → navigate to any node → fork/continue from there.

## Plan

### Phase 1: Session Tree Data Structure
- [x] Create `src/xi/session/tree.cljs` — append-only tree with `id`/`parentId`
  - Tree entry types: `session`, `user-message`, `assistant-text`, `tool-use`, `tool-result`, `turn-end`, `model-change`
  - `append!` — add entry as child of current leaf, advance leaf
  - `branch!` — move leaf pointer to an earlier entry
  - `get-tree` — build tree nodes for visualization
  - `get-branch` — walk from leaf to root, return path entries
  - `persist!` / `load` — JSONL read/write
- [x] Write tests for tree data structure

### Phase 2: Instrument Runtime
- [x] Wire runtime events to tree — on each emit, append matching entry to session tree
- [x] Persist tree JSONL alongside session `.json` meta
- [x] On session clear/new: reset tree
- [x] On session resume: load tree from JSONL

### Phase 3: Tree Selector TUI Component
- [x] Create `src/xi/tui/tree_selector.cljs`
  - Render tree with connectors (`├─`, `└─`)
  - Active path markers (`•`)
  - Filter modes: default, user-only
  - Up/down navigation, Enter to select, ESC to cancel
  - Type-to-search
- [x] Wire ESC handler: when editor empty + not busy → show tree selector
- [x] Wire `/tree` command

### Phase 4: Fork / Navigate
- [x] On tree node selection: branch the session tree
  - User message: leaf=parent, text goes back to editor
  - Other: leaf=selected node
- [x] Provider fork adapters:
  - Claude: copy JSONL → new `cli-session-id` (full copy for v1)
  - Ollama/others: tree-based context rebuild ready (via `build-message-context`)
- [x] Clear event history / re-render chat from tree branch

### Phase 5: Polish
- [ ] Branch summarization (optional LLM call)
- [ ] Labels/bookmarks on nodes
- [ ] Fold/unfold branches
