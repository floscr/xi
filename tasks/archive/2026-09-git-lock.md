# Git staging lock for rooms sharing a repo

Goal: when one room starts touching the git index (`git add`, …), every other
room's index-mutating git op waits until that room commits (or otherwise
leaves the index clean). Read-only git (status/diff/log) stays unblocked.

## Design

- **Lock = staging lease per git dir**, a file at `<git-dir>/xi-staging.lock`
  (per-worktree git dir → cross-process: :7474, :7475, standalone TUI all see
  it). JSON `{room-id label pid acquired-at touched-at}`, created atomically
  (`open 'wx'`). New ns `xi.git-lock` (pure classify + small impure edge).
- **Classify** git argv → `:read | :mutate` (pure, tested). Explicit read-only
  allowlist (status diff log show blame rev-parse ls-files …); everything
  else counts as mutate (safe default).
- **Enforcement (waits, doesn't fail):**
  1. New `:git-lock` extension tool-gate, *first* in the chain (so a rules
     `allow` short-circuit can't skip it): `git_stage_hunks`, `git_commit`,
     `bash` git commands. Async poll (2s) until free, then acquire.
  2. clj `git-fn` (+ the `(sh "git" …)` path): for mutating subcommands post a
     `gateRequest "git"` to the main thread → same async acquire; the worker
     blocks in the existing abort-aware Atomics.wait loop. Runtime check, so
     dynamic args are covered without static scanning.
- **Release:**
  - after any mutating op by the holder, index clean (`git diff --cached
    --quiet`) → release (covers commit, reset, restore --staged)
  - `:agent/turn-end` of the holder with a clean index → release
  - still-staged at turn end → keep holding (agent is waiting on the user)
  - stale: holder pid dead / room gone → stealable
  - `/git-unlock` command to force-release
- **Waiters:** timeout (default 10 min, `deftui`-style config option) → tool
  error naming the holder room + its staged files. Successful waits prefix the
  result with `(waited Ns for git lock held by "<room>")`.

## Decisions

- `git add` is locked like every other index change: the first add takes
  the lock, and other rooms' adds wait.
- Waiters give up after 10 min (config option).
- Broad-add guard ships now (see Steps).
- A room keeps the lock when its turn ends with files still staged.
- Broad adds are refused (not warned) when they'd sweep up another room's edited files.

## Out of scope (note only)

- The user's own terminal git isn't locked.

## Steps

- [x] `xi.git-lock`: classify, lock file read/acquire/release, stale check, index-clean check
- [x] tests: classify + lock state transitions (tmp git dir)
- [x] `:git-lock` extension: tool-gate (wait+acquire), turn-end/tool-result release, `/git-unlock`
- [x] clj worker: `gateRequest "git"` in git-fn + main-thread handler
- [x] broad-add guard: warn on `git add -A`/`.`/`--all`/`-u`, `commit -a` while
      other rooms are active in the same repo
- [x] wire into `xi.config` (first in gate order), config option + docs/config.md
- [x] docs: short section (rules.md or new docs/git-lock.md)
- [x] verify: `bb test` (768 tests, 0 failures; lease lifecycle + two-room gate + broad-add refusal on real tmp repos)
- [ ] live check of the clj worker path after `bb serve:restart`

## Review

- New: `src/xi/git_lock.cljs` (pure classification + lock-file edge),
  `src/xi/ext/git_lock.cljs` (gate, release handlers, `/git-unlock`),
  `test/xi/git_lock_test.cljs`, `docs/git-lock.md`.
- `xi.ext.clj`: runtime-gate wait loop generalized (`await-main-thread!`),
  `git-fn` / `sh-fn` gate + settle around index-mutating git.
- `xi.config`: git-lock first in the server gate chain.
- Not exercised live yet: clj worker ↔ main `gateRequest "git"` round trip
  (unit tests can't spawn the real worker).
