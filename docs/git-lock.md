# Git staging lock

Rooms working in the same repo share one git index. Without coordination,
room A's `git add` followed by room B's `git commit` puts A's work into B's
commit. The git-lock extension (`xi.ext.git-lock`, lease logic in
`xi.git-lock`) serializes index-mutating git ops across rooms. It's a
lighter alternative to worktrees when agents do unrelated work in one
checkout.

## Behavior

- The **first index-mutating git op** a room runs takes the lock: `add`,
  `commit`, `reset`, `restore`, `stash`, `checkout`, `rebase`, …, and any
  unknown subcommand.
- **Other rooms' index-mutating ops wait.** The tool call stays pending and
  the room shows a status line (`⏳ git is locked by "<room>" (staged: …) —
  waiting…`). Once the lock is free the op runs (`🔓 git lock acquired after
  Ns`).
- **Read-only and ref-only git never waits**: `status`, `diff`, `log`,
  `show`, `blame`, `stash list`, `branch`, `tag`, `fetch`, `push`, … (see
  `NON_LOCKING` in `xi.git-lock`).
- **Release** happens once the holder's index is clean. The lock is checked
  after each of the holder's git ops and again at turn end, so a commit,
  `reset` or `restore --staged` frees it.
- **Staged files keep the lock across turns.** If an agent stops with files
  still staged (waiting on you, interrupted), releasing would let the next
  room's commit sweep them up. Waiters keep waiting until the holder commits,
  you unstage, or you run `/git-unlock`.
- **Timeout:** a waiter gives up after `XI_GIT_LOCK_WAIT_SECS` (default 600).
  The agent gets an error naming the holder and its staged files, and should
  tell you.
- **Broad adds are refused** when they would sweep up another room's work.
  `git add -A` / `.` / `--all` / `-u` and `git commit -a` fail with a list of
  the affected files if any dirty file was edited by another room this
  session. The agent must stage its own paths explicitly.
- If the index already had staged files that no room owns (e.g. you staged by
  hand), the room acquiring the lock gets a `⚠` status line: those files will
  be part of its next commit.

## Where it's enforced

| Path | Mechanism |
| --- | --- |
| `git_stage_hunks`, `git_commit`, `bash` git commands | `:tool-gate`, **first** in `xi.config/server` so a rules `:allow` short-circuit can't skip it |
| clj `(git …)` / `(sh "git" …)` | runtime, inside the clj worker: a `gateRequest "git"` blocks the worker (abort-aware `Atomics.wait`) while the main thread waits for the lock; the worker releases it after the op when the index is clean |

Your own terminal git is not covered.

## The lease

A JSON file at `<git-dir>/xi-staging.lock`: `{pid room label acquired-at
touched-at}`. Because it's a file, every Xi process on the machine (main
server, personal agent, standalone TUIs) sees it. It is per-worktree because
each worktree has its own git dir and index. The owner is `{pid, room}`.

It becomes stale and can be taken over when:

- the owning process is dead, or
- nothing has been staged for 60 s (`STALE_CLEAN_MS`). This covers holders that
  finished outside a tracked op, e.g. you committed in a terminal.

`/git-unlock` force-releases the lock for the current room's repo.
