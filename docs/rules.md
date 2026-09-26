# Rules Engine

A declarative, config-driven policy layer. Rules decide what agent tool calls
are **allowed**, **denied**, **nudged**, or **asked** about — replacing scattered
hard-coded gate logic with data you can edit.

A rule is a plain EDN map:

```clojure
{:match  {:tool #{:write :edit} :path #"\.sh$"}
 :action {:type :nudge :message "write babashka scripts, not shell scripts"}}
```

`:on-block` / `:do` are accepted as aliases for `:match` / `:action` (the
notation from the feature request):

```clojure
{:on-block {:type :write :match-file #"\.sh$"}   ; ← alias form (see note below)
 :do       {:type :nudge :message "…"}}
```

> The canonical keys are `:match` and `:action`; prefer them. `:on-block`/`:do`
> map onto them verbatim, so put a full `:match` map under `:on-block` and a
> full `:action` map under `:do`.

## Where rules live (scopes & precedence)

Rules are evaluated **top to bottom; the first match wins.** Sources, in
precedence order:

1. **hard-coded immutable** — agents may never write the rules files, and a few
   always-blocked paths. Not user-editable, cannot be overridden by any rule.
2. **hardened tier** — a small set of non-overridable policy `:deny` rules
   (`:scope :hardened`, `xi.rules.defaults/hardened-rules`), prepended ahead of
   all config/runtime rules so nothing below can click through them. The agent
   cannot disable them; only the operator can, and only at launch via the
   `--no-hardened-rules` CLI flag. Covers `sudo`, remote-copy shells
   (`scp`/`rsync`/`sftp`), and reading SSH private keys under `~/.ssh`.
3. **repo config** — `<repo>/.xi/rules.edn`
4. **global config** — `~/.config/xi/rules.edn`
5. **server-session** — added at runtime, process-local, cleared on restart
6. **session runtime** — added at runtime, room-scoped, persists with the session
7. **built-in defaults** — shipped with xi (`xi.rules.defaults`), lowest
   precedence, so any rule above overrides them.

Config (3–4) sits **above** runtime (5–6), so a rule you commit to a file
overrides a careless "always allow" chosen in the moment. Repo beats global
(more specific wins). Built-in defaults (including the mode-gated plan-mode and
sandbox sets) sit last, so any of your rules wins over a default.

### Hardened tier (non-overridable)

Shipped with xi (`xi.rules.defaults/hardened-rules`), tagged `:scope :hardened`,
prepended ahead of every config/runtime/default rule so **nothing below can
grant an exception** — there is no `[a]llow` button and a softer `ask` rule can
only add friction, never override the deny. Only the operator can turn the tier
off, and only at launch with `--no-hardened-rules` (the agent can't relaunch the
process, so it can't flip it).

- **`sudo`** (`sh`/`bash`) → **deny**. Never allowed from the agent.
- **remote-copy shells** — `scp` / `rsync` / `sftp` (`sh` `:cli`, or matched in a
  `bash` command line) → **deny**.
- **SSH private keys** — reading any file under a `~/.ssh/` dir → **deny**,
  across two surfaces:
  - the structured read tools `read` / `grep` / `find` / `ls` (path-matched), and
  - the **shell** — `bash` and clj `(sh …)` command lines that name a key path
    (e.g. `cat ~/.ssh/id_rsa`, `head`/`base64`/`xxd`/`cp …`), matched in the
    command string. This closes the bypass where a read-only CLI like `cat`
    auto-runs and its path argument is invisible to the structured `:read` guard.

  `*.pub` public keys and `config` / `known_hosts` / `authorized_keys` /
  `environment` stay readable (excluded by the regex); everything else (`id_rsa`,
  `id_ed25519`, custom-named keys, keys in subdirs) is blocked outright. This is
  stricter than the softer "sensitive path" ask below — that one can add friction
  but never grant access here.

### Built-in default rules

Shipped with xi (see `xi.rules.defaults`), overridable by any user rule.

**Behavioral nudges** (ordered first, so they win over the policy gates below on
overlap):

- **`rm` under `/tmp`** → nudge: `/tmp` is a temp filesystem cleared on restart,
  so cleanup is skipped.
- **writes to the Claude auto-memory dir** (`.claude/projects/<…>/memory/`, via
  `write`/`edit`/`bash`) → nudge: xi doesn't use auto-memory, so the write is
  skipped.

**Policy gates** (ported from the old permission-gate extension):

- **sensitive write** — `write`/`edit` into `Mail` / `.ssh` / `.gnupg` /
  `.password-store` → **ask**.
- **protected write** — `write`/`edit` into `.env` / `.git/` / `node_modules/`
  → **ask**.
- **outside write** — `write`/`edit` to a path resolving outside the working
  tree (and tmp) → **ask** with a third `[r]` option that persists an allow-rule
  for the whole target repo.
- **remote shell** — `bash` running `ssh` / `scp` / `rsync` / `sftp` →
  **deny** (hard-blocked).
- **guarded command** — destructive `bash` patterns (`rm -rf`, `sudo`,
  `chmod -R`, `git push`, `kill`, `fs/delete-tree`, …) → **ask**.
- **external MCP tool** — any `mcp__<server>__<tool>` call → **ask**, with an
  informative confirm block (server, tool, and every argument). `[a]lways`
  persists a session allow-rule narrowed to that MCP server + tool. External
  servers are third-party code, so nothing they expose runs without approval.

**Plan mode** (gated by `:when {:plan-mode {:enabled? true}}`, toggled with
`/plan`; the `plan-mode` extension owns the toggle + badge, the policy lives
here): while on, `write`/`edit` to `tasks/todo.md` are allowed, all other
`write`/`edit` and any mutating `bash` (`rm`/`mv`/`cp`/`chmod`/`sudo`/redirects/…)
are **denied**, and reads / grep / find / ls / read-only bash pass through. These
rules sit before the write/bash gates so plan-mode's deny wins over the softer
ask gates.

**Sandbox mode** (gated by `:when {:sandbox {:enabled? true}}`, toggled with
`/sandbox`; the `sandbox` extension owns the toggle + badge and still runs bash
under the OS backend — bwrap/firejail — while these rules own the deny-checks):
while on, `write`/`edit` outside the working tree (`:outside :cwd`) are
**denied**, reads (`read`/`grep`/`find`/`ls`) of credential paths
(`:credential :read` — `.ssh`, `.gnupg`, `.password-store`, …) are **denied**,
and backgrounded `bash` (`cmd &`) is **denied**. These sit before the write/bash
gates so sandbox's hard denies win over the softer asks.

The server-control tasks (`bb serve:restart` / `serve:stop`) are **not** rules —
they're handled specially by the `permission-gate` extension (detached run so the
agent's own server can be killed cleanly).

To opt back in / silence a gate, add a higher-precedence `:allow` rule for the
same match.

A rules file is either a vector of rules or `{:rules [ … ]}`:

```clojure
;; ~/.config/xi/rules.edn
[{:match {:tool :read :dir "~/code/projects"} :action {:type :allow}}
 {:match {:tool :write :path #"\.sh$" :repo "config/dotfiles"}
  :action {:type :allow}}]
```

## `:match` fields

All present fields are **ANDed**; an absent field is no constraint.

| Field         | Matches                                                              |
|---------------|---------------------------------------------------------------------|
| `:tool`       | tool kind — keyword or set: `:write :edit :read :grep :find :ls :bash :clj :sh :mcp :other` |
| `:path`       | target file path — **regex** (`re-find`, partial) or **glob string** (full match: `*`=one segment, `**`=any, `?`=one char) |
| `:command`    | bash command / clj code / clj shell-out command — **regex** (`re-find`) or **string** (substring) |
| `:cli`        | shell-out binary (first token of a `clj` `(sh …)` / background command) — **string** (exact), **set** (membership), or **regex** (`re-find`); only `:sh` requests carry `:cli` |
| `:repo`       | git-root path **suffix** of the target/effective cwd (e.g. `"config/dotfiles"`) |
| `:dir`        | absolute **path prefix** of the effective cwd (`~` expanded)         |
| `:mcp-server` | MCP server id (for `mcp__<server>__<tool>` calls) — string/glob     |
| `:mcp-tool`   | MCP tool name — string/glob (`"*"` = any)                            |
| `:when`       | submap predicate over room ext state; a map value matches recursively (nested submap, ignoring extra keys), e.g. `{:plan-mode {:enabled? true}}` |
| `:node`       | tree-sitter AST predicate (opt-in) — `{:type … :name … :contains …}` |
| `:outside`    | location predicate (opt-in) — `:cwd` matches when the target path resolves outside the effective cwd (and tmp); symlinks are canonicalized |
| `:credential` | credential-path predicate (opt-in) — `:read` matches when the target path resolves inside a hidden credential dir (`.ssh`, `.gnupg`, `.password-store`, …); symlinks are canonicalized |

### Effective working directory

`sh` / `process/start` / `process/poll-until` (in the `clj` tool) accept a
leading `{:dir …}` opts map. `:repo` / `:dir` / `:node` matching resolves
against the **effective cwd** — the call's `:dir` when present, else the room
cwd — so a repo-scoped rule still matches a command that `:dir`s into that repo,
and a `:dir` hop into an unlisted directory does not leak an unrelated grant.
The `:dir` itself is treated as a read.

### Tree-sitter node targeting (`:node`)

A `:node` matcher is **opt-in**: only when some rule carries one does the store
parse the target with tree-sitter (`xi.rules.nodes` → the `xi-treesitter` CLI)
and populate the request's `:nodes` — a seq of `{:type <ts-node-type> :name
<name-field> :text <source>}`. The matcher then checks each `:node` subfield
against those nodes: `:type` (regex `re-find` or glob-string full-match on the
raw tree-sitter node type), `:name` (regex `re-find` or glob-string full-match on
the node's `name` field), `:contains` (regex `re-find` or substring on the node's
source). It resolves per tool:

- **`edit`** — the edit's `oldText` is located in the target file to get its line
  range; the **enclosing named node(s)** spanning that range are matched (so a
  rule can gate edits to a particular function/class).
- **`write`** — the new `:content` is parsed (via a temp file) and the
  **top-level defs** are matched.

Only languages with an installed grammar are parsed; when treesitter, the
grammar, or the language is unavailable the request's `:nodes` is nil and a
`:node` rule simply never matches (fail-open — pair it with a broader
`:tool`/`:path` rule if you need a hard guarantee).

### `clj` shell-outs (`:sh` + `:cli`)

The `clj` tool doesn't hand the engine its whole `:code`; instead it pre-scans
the script and models **each** literal `(sh …)` / background command as its own
synthetic request `{:tool :sh :cli <binary> :command <cmd>}`. So `:cli` /
`:command` rules under `:tool :sh` target individual shell-outs, not the Clojure
source (a `:tool :clj` rule still matches the raw code). A `:sh` `:allow`
pre-approves that binary (skips its confirm); a `:sh` `:deny` blocks the eval
with the rule message. Session grants from `/clj allow <cli>` (and `:always`
answers) are stored exactly as `{:tool :sh :cli "<cli>"} → :allow` session
rules, so they appear in `/rules`. Read-only auto-run CLIs (`ls`, `cat`, `git`,
`rm`, `ss`, …) keep running through the clj tool's own autorun/escalation path
and are not short-circuited by a `:sh` `:allow`.

## `:action` types

| `:type`  | Effect                                                                  |
|----------|-------------------------------------------------------------------------|
| `:allow` | Force-allow: the call runs and the **remaining gates are skipped**.     |
| `:deny`  | Block with an error result (`:message` shown to the agent).             |
| `:nudge` | Block with a **non-error** steering result — `:message` redirects the agent without signalling failure. |
| `:ask`   | Raise a confirm dialog. When no `:message` is given, an informative default is built from the request (MCP server/tool/arguments, else the bash/clj command, else the target path). `:options` defaults to `[:yes :no :always]`; answering `:always` persists a session allow-rule for the same call (narrowed to the MCP server + tool for MCP calls), and `:repo` (when the target is in a git repo) persists one scoped to the whole repo. |

```clojure
{:match {:tool :bash :command #"\bgit push\b"}
 :action {:type :ask :message "Push to remote?" :options [:yes :no :always]}}
```

## Managing rules at runtime

- `/rules` or `/rules list` — show the effective ruleset in precedence order.
- `/rules reload` — clear the file cache (files are otherwise mtime-cached).

Runtime rules are added programmatically (e.g. the `:always` answer above, or
the recommend-a-rule flow) via the `:ext.rules/add` event with `:scope :session`
or `:scope :server`.

## Recommend a rule from a guard dialog

Every `:ask` rule's confirm dialog carries an extra **Recommend a rule** option
(key `?`) alongside allow/deny/always. Choosing it:

1. Safely **denies** the current tool call (nothing runs).
2. Spawns a background sub-agent with the guarded call's context (tool, path,
   command, cwd, repo, MCP server/tool) and the rule schema, asking it to draft
   the single best-fit rule.
3. When the sub-agent finishes, an editable **form dialog** opens pre-filled with
   the recommended rule (EDN) and a scope field (`session` / `repo` / `global` /
   `server`). Edit either, then Save.
4. Saving writes the rule directly — runtime scopes into app state, config scopes
   (`repo`/`global`) appended to the on-disk rules file — **bypassing the
   tool-gate**, so the immutable hard-block is never involved. Re-run the
   original call to pick up the new rule.

Regex literals (`#"…"`) in a recommended or file rule are parsed with the full
Clojure reader, so they round-trip through save/load correctly.

## The immutable hard-block

Agents can **never** write the rules files
(`~/.config/xi/rules.edn`, `<repo>/.xi/rules.edn`) — not via `write`/`edit`, and
not via a `bash`/`clj` command that both references a rules file and contains a
write token (`>`, `tee`, `sed -i`, `cp`, `mv`, `dd`, `spit`, `writeFileSync`,
`rm`, …). This check runs first and cannot be overridden by any `:allow` rule.
Edit the rules files yourself, or use the recommend-a-rule flow on a guard
dialog.
