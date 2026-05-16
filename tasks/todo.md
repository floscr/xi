# Plan: Functional Refactoring of Xi Codebase

## Context
Xi has accumulated global mutable state, duplicated utility functions, and imperative patterns that should be refactored toward idiomatic Clojure. Each step is a small, testable, independently committable change.

## Steps

### Phase 0: Housekeeping
- [x] Remove stale `compaction_test.cljs` tests referencing deleted `needs-compaction?`

### Phase 1: Extract shared utilities
- [x] Create `xi.util` namespace with `truncate`, `claude-model?`, `extract-text-content`, `strip-mcp-prefix`
- [x] Write tests for all extracted functions in `test/xi/util_test.cljs`
- [x] Update `runtime/commands.cljs` to use `xi.util/truncate`, `xi.util/extract-text-content`
- [x] Update `client/tui.cljs` to use `xi.util/truncate`, `xi.util/strip-mcp-prefix`
- [x] Update `loop.cljs` to re-export from `xi.util/claude-model?`
- [x] Remove private duplicate `claude-model?` from `runtime/commands.cljs`
- [x] Deduplicate `claude-executable` resolution into a shared location

### Phase 2: Make `format-scrollback` functional
- [x] Rewrite `format-scrollback` using `reduce` instead of atoms
- [x] Add tests for `format-scrollback` covering all event types

### Phase 3: Remove println side-effects from hooks
- [x] Remove `println` from `plan-mode` hook handlers, return data instead
- [x] Update plan-mode tests

## Results

All phases completed. 5 commits:

1. **fix(test)**: Remove stale compaction tests (4 errors eliminated)
2. **refactor**: Extract `xi.util` with 18 tests for shared pure functions
3. **refactor**: Wire 4 consumer files to use `xi.util`, removing ~40 lines of duplication
4. **refactor**: Deduplicate `claude-executable` resolution between provider and compaction
5. **refactor**: Rewrite `format-scrollback` as pure `reduce` (+11 tests)
6. **refactor**: Remove `println` side-effects from plan-mode hooks

Test suite: 114 → 144 tests, all passing, 0 warnings.
