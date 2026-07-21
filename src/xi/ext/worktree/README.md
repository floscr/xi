# worktree

The `/worktree` command — spin a room off into a fresh **git worktree** and
work there, then rebase it back.

## Commands

| Command | Effect |
| --- | --- |
| `/worktree <prompt…>` | Create a branch + linked worktree from the room's repo, switch the room's cwd into it, and (when a prompt is given) launch the agent there. |
| `/worktree merge` | **Rebase** the worktree branch onto the main branch and fast-forward the main tree into it (linear history — no merge commit), cd the room back, then **confirm** before removing the worktree + deleting the branch. If the rebase conflicts it is aborted and the worktree is left untouched to resolve. |
| `/worktree list` | List the repo's worktrees. |
| `/worktree remove` | Remove the current worktree (confirmed) and cd back to the main tree. |

Bare `/worktree` (no prompt) just creates the worktree and switches into it —
no agent turn is started.

## Naming & location

- **Branch**: a slug of the first words of the prompt (e.g. *"Add dark mode
  toggle"* → `add-dark-mode-toggle`), or `wt-<timestamp>` when there's no
  prompt. Colliding names get a `-2`, `-3`, … suffix.
- **Path**: a sibling of the repo — `<repo-parent>/<repo-name>-<branch-slug>`
  (matches the project's existing `xi-*` worktree convention).

## The isolated-checkout heads-up

A worktree is a **separate checkout**, so the main repo's build/watch does not
cover it and any long-running services must not reuse the main tree's ports.
When a prompt is given, the agent is told exactly that, and any
`worktree`-titled section of the repo's `AGENTS.md` is inlined into the prompt
so project-specific build/port conventions travel with it
(`build-worktree-prompt` + `agents-worktree-guidance`).

## Resuming worktree sessions

Sessions are saved under a directory keyed by their cwd, so work done inside a
worktree lands in that worktree's own session dir. `session/list-sessions`
therefore scans the **whole git project** — the main tree plus every linked
worktree (`session/git-project-cwds`, via `git worktree list`) — so `/resume`
from the main repo surfaces sessions that were started inside its worktrees
(and vice versa). Picking such a session also switches the room's cwd into the
worktree it belongs to (guarded to the same git project), so the resumed agent
runs in the right checkout.

## How it fits the architecture

- `core.cljs` — the extension: pure command + pure event handlers
  (`:worktree/created`, `:worktree/switch`) and the impure effects
  (`:worktree/create|merge|remove|list`). Cwd changes ride the room state, so
  the agent and every tool run in the worktree automatically.
- `git.cljs` — the impure edge: thin `Bun.spawnSync` wrappers returning plain
  `{:ok :err :code}` data.
- The removal **confirm** uses `ext.core/create-dialogs`' `ask!`, threaded in
  via `server-extensions` in `xi.cli`. Headless / client-mirror (no `ask!`)
  resolves to "no", so nothing is removed without an explicit yes.
