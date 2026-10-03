# Commands

Slash commands are **data composed at assembly time** — there is no global
registry or registration atoms. `xi.commands` defines the built-ins;
extensions contribute commands via their `:commands` key; `xi.cli` merges
them when building an app.

## Command shape

```clojure
{:name        "model"                  ;; part after /
 :description "Show or set model"      ;; shown in /help and TUI completion
 :handler     (fn [state ctx] …)}      ;; pure event handler
```

The handler is a normal pure handler: `(fn [state {:keys [room-id args
commands]}]) → {:state … :effects […]} | nil`. `:args` is the trailing
string after the command name (or `nil`), `:commands` is the full merged
command list (so `/help` can enumerate it).

## Dispatch flow

```
editor text "/model haiku"
      │  :input/submit
      ▼
parse-input  ──→ :command/run {:name "model" :args "haiku"}
      │              │
      │              ▼
      │     make-command-run — closed over the merged command
      │     vector (built-ins + extension commands; built-ins
      │     win on name clashes)
      │              │
      │              ▼
      │     (:handler cmd) state {:room-id :args :commands}
      │
      └─ not a command → :prompt/submit (pending images ride
         along via the :image/process effect)
```

`parse-input` (`xi.commands`) routes raw editor text: `/name args…` becomes
a `:command/run` event, anything else a `:prompt/submit`.

## Built-in commands

Defined in `xi.commands/built-in-commands`:

| Command | Description |
|---|---|
| `/help` | Show available commands |
| `/model` | Show or set model |
| `/resume` | Resume a previous session |
| `/sessions` | List previous sessions |
| `/favorites` | List favorited sessions |
| `/favorite` | Toggle favorite on the current session |
| `/new` | Start a new session in the current cwd (TUI: Alt+N, kitty keyboard protocol; web: Alt+N) |
| `/clear` | Clear current session |
| `/fork` | Split the conversation into a new session |
| `/truncate` | Summarize conversation to reduce context ([compaction.md](compaction.md)) |
| `/summary` | Describe what this session is about (cheap model) |
| `/prompt` | Show system prompt |
| `/tree` | Navigate session history ([session-tree.md](session-tree.md)) |
| `/events` | Show the event log for this session |
| `/buffers` | Switch buffer view (chat / logs / prompt / diff) |
| `/cd` | Change working directory |
| `/debug` | Copy debug info to clipboard |
| `/holds` | Show who holds this room's shared resources (the git index; see [git-lock.md](git-lock.md)) |
| `/release` | Force-release holds on this room's shared resources |
| `/reload` | Restart Xi (picks up recompiled code) |
| `/quit` | Exit Xi |

Extensions add more (e.g. `/plan`, `/commit`, `/diff`, `/kb` — see
[extensions.md](extensions.md)). `/diff` (diff extension) takes `git` \|
`staged` \| `unstaged` \| `session-edits` \| `session-git` \|
`session-commits` \| `<ref>`; no args → session diff. `session-git` = files
edited this session that are still uncommitted.

## Extension commands

An extension lists commands under its `:commands` key. At assembly,
`xi.commands/command-handlers` receives the extension commands and builds
the `:command/run` handler over the merged vector:

```clojure
(commands/command-handlers extra-commands)
;; → {:input/submit … :command/run … :ui/menu-open … :session/resumed … …}
```

`xi.commands/all-commands` returns the same merged vector for TUI
completion and `/help`.

## Client-side interception

In client mode (`xi join` / web), commands are forwarded to the server like
any other event — except `/quit` and `/reload`, which `ws-transport`
intercepts locally because they act on the client process.

## Adding a built-in command

Add a `cmd-*` handler and an entry to `built-in-commands` in
`xi.commands`. Handlers either update state directly (e.g. open a buffer
under `[:rooms room-id :ui :buffers]`) or return effects (e.g.
`[[:diff/load …]]`) when they need I/O.
