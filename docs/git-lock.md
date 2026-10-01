# Holds and the git staging lock

Rooms working in the same repo share one git index. Without coordination,
room A's `git add` followed by room B's `git commit` puts A's work into B's
commit. The **git-index hold** serializes index-mutating git ops across rooms.
It's a lighter alternative to worktrees when agents do unrelated work in one
checkout.

## Holds

A hold (`xi.holds`) is a releasable, cross-process block on a shared
resource. While one room holds it, every other room's calls that touch the
same resource **wait** until it is released; the holder's own calls pass.
Holds are not policy: the rules decide whether a call may run at all, and a
hold only orders access. It never grants anything.

The registry is fixed in core (`xi.holds/registry`). The git index is
currently the only hold. A hold is a data map:

| Key | Meaning |
| --- | --- |
| `:ops` | `(fn [tool-name arguments cwd] → [op])`: what a tool call does under the hold (`[]` = unaffected) |
| `:key` / `:cwd-key` | the resource an op / a room's cwd touches (git: the worktree toplevel) |
| `:lease-path` | the lease file for a key |
| `:stale?` | staleness beyond a dead owner process |
| `:settle?` | release condition, checked after each held call and at turn end |
| `:refuse` | hook: refuse a call outright instead of waiting |
| `:detail` / `:on-acquire` / `:hint` | text for status lines and errors |
| `:wait-ms` | give up waiting after this |

**Where it's enforced:** `xi.holds/wrap` runs around every tool exec-fn
(`xi.tools.registry/with-extensions`, so every provider). It runs after the
rules have allowed the call, so a rules `:allow` can't skip it. The clj tool
runs git mid-eval inside its worker thread. There, `(git …)` / `(sh "git" …)`
send a `gateRequest "git"` that blocks the worker (abort-aware
`Atomics.wait`) while the main thread runs `xi.holds/acquire!`. The worker
settles the hold itself after the op.

Your own terminal git is not covered.

## Git-index behavior

- The **first index-mutating git op** a room runs takes the hold: `add`,
  `commit`, `reset`, `restore`, `stash`, `checkout`, `rebase`, …, and any
  unknown subcommand. It applies to `git_stage_hunks`, `git_commit`, `bash`
  git commands and clj `(git …)` / `(sh "git" …)`.
- **Other rooms' index-mutating ops wait.** The tool call stays pending and
  the room shows a status line (`⏳ git index is held by "<room>" (staged: …)
  — waiting… To unlock: commit or unstage there, or run /release.`). Once
  it's free the op runs (`🔓 git index acquired after Ns`).
- **Read-only and ref-only git never waits**: `status`, `diff`, `log`,
  `show`, `blame`, `stash list`, `branch`, `tag`, `fetch`, `push`, … (see
  `NON_LOCKING` in `xi.git-lock`).
- **Release** happens once the holder's index is clean. It is checked after
  each of the holder's git ops and again at turn end, so a commit, `reset` or
  `restore --staged` frees it.
- **Staged files keep the hold only while the holder's turn is running.** If
  an agent stops with files still staged (waiting on you, interrupted), its
  lease is flagged idle at turn end: nobody is there to release it, so the
  next room takes it over instead of waiting. That room gets the `⚠` status
  line below, because the leftover staged files will be part of its next
  commit. If the holder resumes first, its next git op clears the flag and it
  holds the index again.
- **Timeout:** a waiter gives up after `XI_GIT_LOCK_WAIT_SECS` (default 600).
  The agent gets an error naming the holder and its staged files, and should
  tell you. A wait also stops when the waiting room's turn ends.
- **Broad adds are refused** (the hold's `:refuse` hook) when they would sweep
  up another room's work. `git add -A` / `.` / `--all` / `-u` and
  `git commit -a` fail with a list of the affected files if any dirty file was
  edited by another room this session. The agent must stage its own paths
  explicitly.
- If the index already had staged files that no running room owns (you staged
  by hand, or an idle holder left them), the room taking the hold gets a `⚠`
  status line: those files will be part of its next commit.

## The lease

A JSON file at `<git-dir>/xi-staging.lock`: `{pid room label acquired-at
touched-at idle-at}` (`xi.holds.lease`). Because it's a file, every Xi process on the
machine (main server, personal agent, standalone TUIs) sees it. It is
per-worktree because each worktree has its own git dir and index. The owner
is `{pid, room}`.

It becomes stale and can be taken over when:

- the owning process is dead,
- the owning room's turn ended with the hold unsettled (`idle-at`, set at
  turn end and cleared when that room runs its next index-mutating op), or
- nothing has been staged for 60 s (`STALE_CLEAN_MS`). This covers holders that
  finished outside a tracked op, e.g. you committed in a terminal.

## Commands

- `/holds`: who holds this room's resources (the git index of its cwd), and
  whether that holder is idle.
- `/release`: force-release them.
