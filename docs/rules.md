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
   (`scp`/`rsync`/`sftp`), reading SSH private keys under `~/.ssh`, and an
   always-confirm on changes to xi rules files outside the canonical paths.
3. **repo config** — `<repo>/.xi/rules.edn`
4. **global config** — `~/.config/xi/rules.edn`
5. **server-session** — added at runtime, process-local, cleared on restart
6. **session runtime** — added at runtime, room-scoped, persists with the session
7. **defaults** — the built-in default rules (`xi.rules.defaults`), or the
   bundles a rules file picks via [`:defaults`](#choosing-defaults-defaults);
   lowest precedence, so any rule above overrides them.

Config (3–4) sits **above** runtime (5–6), so a rule you commit to a file
overrides a careless "always allow" chosen in the moment. Repo beats global
(more specific wins). Built-in defaults (including the mode-gated plan-mode
set) sit last, so any of your rules wins over a default.

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
- **shell interpreters via clj `(sh …)`** — `bash` / `sh` / `zsh` / `fish` /
  `dash` / `ksh` / `csh` / `tcsh` / `ash` / `mksh` as the `:cli` → **deny**.
  `(sh "bash" "-lc" "grep … | grep … | head")` runs a login/interactive shell to
  smuggle a whole pipeline past clj's argv-only model — the engine only ever
  sees `:cli "bash"` and one opaque `:command` blob, so its per-command scan
  can't reason about what actually runs. Commands must go argv-style, one per
  `(sh "cmd" "arg" …)` call, with pipelines composed in Clojure or via the
  builtin helpers (`(grep …)`, `(curl …)`, `(jq …)`, …). Matched by `:cli`, so
  the real `bash` tool (`:tool :bash`) is untouched.
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
- **xi rules files elsewhere** — any change to a `rules.edn` that is an xi
  rules file (it carries `:version`) → **ask**, every time, `[y]es` / `[n]o`
  only. Covers files the immutable hard-block doesn't, e.g. a dotfiles source
  that gets copied to `~/.config/xi/rules.edn`. Matched via `:xi-rules-file`:
  `write`/`edit` when the existing file is versioned or the new text introduces
  `:version` (creating or migrating one), and `bash`/`clj` commands that carry
  a write token and name an existing versioned `rules.edn`. Other tools'
  `rules.edn` files (no `:version`) are left alone. Being hardened, no session
  grant or config `:allow` can skip the prompt.

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
- **chained bash** — a `bash` command that composes shell commands (pipes,
  `;` / `&&` / `&`, `$(…)`, backticks, several lines, a leading `VAR=`;
  `:chained true`) → **deny**, pointing at the clj tool. `bash` is hidden from
  the model while the clj extension is on, so this only applies where bash is
  still offered. To use bash without clj, drop `bash-chained` from `:defaults`
  or add an allow rule.
- **untrusted `bb.edn`** — a `bb` tool call whose project `bb.edn` isn't in the
  trust store (`:bb-trusted false`) → **ask**, naming the `bb.edn`. `[a]lways`
  trusts that `bb.edn` (the same as `/clj trust-bb`; content-addressed, so an
  edit asks again) instead of saving a session rule. With nobody to answer,
  the call is refused (`:unanswered :deny`).
- **guarded command** — destructive `bash` patterns (`rm -rf`, `sudo`,
  `chmod -R`, `git push`, `kill`, `fs/delete-tree`, …), also in a `bb` task's
  command line → **ask**.
- **read-only `sed` in a repo** — clj `(sh "sed" "-n" "<addr>p" file…)` (line
  range, `$`, or `/re/` addresses, `;`-separated `p` commands, e.g.
  `sed -n 3060,3420p src/foo.cljs`) whose effective cwd is inside a git repo
  → **allow**. It's `:command`-scoped, so clj grants that exact command only.
  `-i`/`--in-place`, sed's `w`/`e` commands, any other flag, and a missing file
  operand don't match and fall to the base `(sh …)` ask.
- **file management in a repo** — clj `(sh …)` of `mv` / `cp` / `mkdir` /
  `touch` / `rmdir` whose operands all stay inside the repo or tmp
  (`:within :repo`) → **allow**, as an exact-command grant. Anything reaching
  outside, into `.git/` / `.xi/`, or the repo root itself asks as usual. Not
  covered: `ln` (a relative link target resolves against the link's own
  directory, so the operand check can't vouch for it) and `chmod` (a
  permission change isn't a content change — `+x` makes a written file
  runnable, `u+s` / `o+r` escalate or expose — so it always confirms).
- **removing git-tracked content** — clj `(sh "rm" …)` whose every operand is
  tracked content inside the repo (`:tracked :git`: a file in the index, or a
  directory holding only indexed files — recoverable from git) → **allow**, as
  an exact-command grant. `(sh "rm" …)` auto-runs regardless; what this
  changes is the clj builtin `(rm dir)`: its recursive-delete confirm is
  skipped for such a directory, while one holding anything untracked or
  ignored still asks. See [clj-tool.md](clj-tool.md#sh--permissions).
- **script / inline-code execution** (`script-exec`) — an interpreter run with
  inline code or a script file → **ask**, `[y]es` / `[n]o` only, refused when
  nobody can answer (`:unanswered :deny`). The interpreter reads the script
  itself, so the read/write gates and path guards never see what it does (a
  `/tmp/x.clj` that `slurp`s `~/.config/…` runs outside every check). Matches
  `bash`, clj `(sh …)` / background commands and the `bb` tool by command line
  (`xi.rules.defaults/script-exec-res`):
  - **plain interpreters** — `node`/`nodejs`, `python`, `pypy`, `ruby`, `perl`,
    `php`, `lua`, `luajit`, `Rscript`, `elixir`, `julia`, `sbcl`, `guile`,
    `racket`, `nbb`, `tsx`, `ts-node`, `java`, `jshell`, `groovy` — any run
    asks (`-e`, `-c`, `-p`, a script file, `-m`, `-jar`, …) except a bare
    `--version` / `-v` / `-V` / `-version` / `--help` / `-h`. Version suffixes
    (`python3.12`) and path prefixes (`/usr/bin/node`) match too.
  - **`bb`** — `-e`/`--eval`, `-f`/`--file`, `-i`/`-I`/`-o`/`-O`, `-m`/`--main`,
    `-x`/`--exec`, `--init`, `--repl`, `--nrepl-server`, `--socket-repl`, or a
    script operand (`*.clj`/`.cljs`/`.cljc`/`.bb`, or a `./`, `../`, `~/`, `/`
    path). `bb <task>` stays free — its code is the trusted `bb.edn`.
  - **`bun`** — `-e`/`--eval`/`-p`/`--print`, `x`/`repl`/`exec`, or a script
    operand (`*.js`/`.ts`/`.mjs`/… or a path); `bunx` always. `bun test`,
    `install`/`add`/`remove`/…, `build` and `bun run <package script>` stay free.
  - **`deno`** — `eval` / `run` / `repl` / `serve` / `x`; `task`, `test`, `fmt`,
    `lint`, … stay free.
  - **`clojure` / `clj`** — `-M` / `-X` / `-T`, `-e`/`--eval`, `-i`/`--init`,
    `-m`/`--main`, `-r`/`--repl`, or a `*.clj` script; `-Spath` etc. stay free.

  It is command-scoped, so clj asks per exact command even when the CLI is
  allowlisted or `bb` is trusted via its `bb.edn`. There is deliberately no
  `[a]lways`: a session grant is a CLI-wide allow that would lift the gate for
  every later script. To pre-approve specific scripts, add a higher-precedence
  **arg-scoped** allow rule:

  ```clojure
  {:match {:tool :sh :cli "bb" :command #"^bb -f scripts/"} :action {:type :allow}}
  ```

  Any `:allow` for the CLI above the defaults lifts the gate — including a
  config rule without `:command`, `/clj allow <cli>`, and the per-CLI dialog's
  `[a]lways` — so scope allows by `:command`. Not covered: wrappers
  (`env node …`, `npx`, `xargs`, …), which fall to the per-CLI ask unless
  allowlisted, and runtimes outside the list.
- **external MCP tool** — any `mcp__<server>__<tool>` call → **ask**, with an
  informative confirm block (server, tool, and every argument). `[a]lways`
  persists a session allow-rule narrowed to that MCP server + tool. External
  servers are third-party code, so nothing they expose runs without approval.
- **sub-agent spawn** — `spawn_subagent` (`:tool-name`) → **ask**; the dialog
  shows the task. `[a]lways` persists a session allow-rule pinned to
  `spawn_subagent` (never to `:other` at large). A sub-agent's own tool calls
  auto-deny every ask, so sub-agents can't spawn sub-agents.

**Plan mode** (gated by `:when {:plan-mode {:enabled? true}}`, toggled with
`/plan`; the `plan-mode` extension owns the toggle + badge, the policy lives
here): while on, `write`/`edit` to `tasks/todo.md` are allowed, all other
`write`/`edit` and any mutating `bash` (`rm`/`mv`/`cp`/`chmod`/`sudo`/redirects/…)
are **denied**, and reads / grep / find / ls / read-only bash pass through. These
rules sit before the write/bash gates so plan-mode's deny wins over the softer
ask gates.

**Server control**: `bb serve:restart` / `serve:stop` via `bash`, the `bb`
tool, or clj `(sh …)` → **ask** (`[y]es` / `[n]o`, no always). Once approved,
the executor runs it detached (`xi.server-control`) and returns an explicit
result, so the agent's own turn survives killing its server. The rule is
`:command`-scoped, so clj confirms the exact command even when `bb` is an
allowed CLI.

To opt back in / silence a gate, add a higher-precedence `:allow` rule for the
same match — or drop its bundle from the default tier via `:defaults` (below).

## Rules files

A rules file is a map with a **required** `:type` and `:version`:

```clojure
;; ~/.config/xi/rules.edn
{:type    :xi/rules
 :version 1
 :rules   [{:match {:tool :read :dir "~/code/projects"} :action {:type :allow}}
           {:match {:tool :write :path #"\.sh$" :repo "config/dotfiles"}
            :action {:type :allow}}]}
```

| Key         | Meaning                                                                 |
|-------------|-------------------------------------------------------------------------|
| `:type`     | Required, always `:xi/rules`. Identifies the file as xi's rules file (the user config file is `:xi/config`, see [config.md](config.md#agent-profiles-configxiconfigedn)); another tool's `rules.edn` can't be misread as policy. |
| `:version`  | Required. The rules-file format version — currently `1`. It version-locks the file: when the format (or a default-bundle alias) changes, the version bumps and an old file errors instead of being silently misread. |
| `:rules`    | Vector of rule maps, at this file's config precedence (repo / global). |
| `:defaults` | Optional. Replaces the default tier — see [below](#choosing-defaults-defaults). |

User extensions are **not** enabled here any more: the list lives under
`:extensions` in `~/.config/xi/config.edn` (see
[user-extensions.md](user-extensions.md#enabling)). A rules file that still
carries `:extensions` is invalid (fails closed with a pointer).

**An invalid file fails closed.** A missing or wrong `:type`, a missing or
unsupported `:version`, a bare
rule vector (the pre-version format), an unknown top-level key, an alias under
`:rules`, an unknown `:defaults` alias, or unparseable EDN makes the whole
file invalid. It is replaced by a single catch-all **deny** in its tier whose
message names the file and the problem — so every tool call is blocked (the
hardened tier still runs above it) until the file is fixed. A broken file never
silently drops your own deny rules. `/rules list` shows the stand-in rule.
Agents can't edit rules files, so the fix is always the user's.

### Choosing defaults (`:defaults`)

The built-in defaults are grouped into named **bundles**, addressed by alias
keywords `:xi.rules.defaults/<name>`. A rules file's `:defaults` vector
replaces the whole default tier — list the bundles you want, in precedence
order, optionally mixed with inline rule maps:

```clojure
{:type     :xi/rules
 :version  1
 :rules    [ … ]
 :defaults [:xi.rules.defaults/plan-mode
            :xi.rules.defaults/write-gates
            {:match {:tool :bash :command #"\bgit push\b"}
             :action {:type :ask :message "Push?"}}
            :xi.rules.defaults/clj-sh]}
```

- The repo file's `:defaults` wins over the global file's; with neither, the
  built-in set applies (`xi.rules.defaults/default-aliases`, below).
- `:defaults []` turns the default tier off entirely.
- The tier stays at the **lowest** precedence, so session `[a]lways` grants
  still beat a bundle's `:ask`. (That's why aliases live under `:defaults`,
  not `:rules`: inline in `:rules` the catch-all asks would sit above — and
  shadow — every runtime grant.)
- Order matters (first match wins): keep `plan-mode` before the write/bash
  gates, and the nudges first, as the built-in order does. Dropping
  `plan-mode` means `/plan` no longer enforces anything; dropping
  `subagent-confirm` lets the agent spawn sub-agents unasked.
- The `sandbox-mode` alias is gone (the sandbox extension was removed); a file
  that still lists it fails closed with an unknown-alias error.

The built-in default tier, in order:

```clojure
[:xi.rules.defaults/tmp-cleanup
 :xi.rules.defaults/no-auto-memory
 :xi.rules.defaults/extension-credentials
 :xi.rules.defaults/extension-data
 :xi.rules.defaults/xi-sessions
 :xi.rules.defaults/claude-sessions
 :xi.rules.defaults/plan-mode
 :xi.rules.defaults/write-gates
 :xi.rules.defaults/bash-chained
 :xi.rules.defaults/bb-trust
 :xi.rules.defaults/bash-guards
 :xi.rules.defaults/server-control
 :xi.rules.defaults/mcp-confirm
 :xi.rules.defaults/subagent-confirm
 :xi.rules.defaults/extension-sh
 :xi.rules.defaults/net-confirm
 :xi.rules.defaults/browser-confirm
 :xi.rules.defaults/script-exec
 :xi.rules.defaults/clj-sh]
```

| Alias (`:xi.rules.defaults/…`) | Rules | Covers |
|---|---|---|
| `tmp-cleanup`        | 1 | nudge: `rm` under `/tmp` |
| `no-auto-memory`     | 2 | nudge: writes into the Claude auto-memory dir |
| `extension-credentials` | 1 | deny: user extensions reading/writing credential paths (`xi.paths/HIDDEN_PATHS`) |
| `extension-data`     | 1 | allow: user extensions reading/writing their own data dir |
| `xi-sessions`        | 1 | allow: read/ls/grep/find under `~/.config/xi/sessions` (lifts clj's hidden-path block for that subtree only; user extensions stay denied by `extension-credentials`) |
| `claude-sessions`    | 1 | allow: read/ls/grep/find under `~/.claude/projects` (Claude CLI transcripts; writes stay gated) |
| `plan-mode`          | 3 | plan mode's allow-plan-file / deny-writes / deny-mutating-bash |
| `sensitive-writes`   | 1 | ask: write into Mail / .ssh / .gnupg / .password-store |
| `protected-writes`   | 1 | ask: write into .env / .git/ / node_modules/ |
| `outside-writes`     | 1 | ask: write outside the repo (with `[r]`) |
| `write-gates`        | → | composite: `sensitive-writes` `protected-writes` `outside-writes` |
| `bash-chained`       | 1 | deny chained/piped bash (use the clj tool) |
| `bb-trust`           | 1 | ask before running `bb` with an untrusted `bb.edn`; `[a]lways` trusts it |
| `bash-guards`        | 2 | deny remote shells, ask on destructive bash / bb task command lines |
| `server-control`     | 1 | ask on `bb serve:restart` / `serve:stop` (bash, bb tool, clj sh) |
| `mcp-confirm`        | 1 | ask on every external MCP tool call |
| `subagent-confirm`   | 1 | ask on every `spawn_subagent` call |
| `extension-sh`       | 1 | ask on every user-extension shell-out (before `clj-sh`: its auto-run list relies on clj's own confinement) |
| `net-confirm`        | 1 | ask on every user-extension network request (`[a]lways` pins extension + host) |
| `browser-confirm`    | 1 | ask on every user-extension headless-Chrome visit (`[a]lways` pins extension + host) |
| `script-exec`        | 6 | ask before an interpreter runs inline code or a script file (`bb -f`, `node -e`, `python x.py`, `bun x.ts`, …); no `[a]lways`, refused headless |
| `sh-read-only`       | 1 | allow read-only CLIs via clj `(sh …)` |
| `repository-scripts` | 3 | allow read-only `sed -n …p`, in-repo `mv`/`cp`/`mkdir`/`touch`/`rmdir`, and `rm` of git-tracked content (ordered before `sh-read-only` so its arg-scoped `rm` allow is reachable) |
| `localhost-curl`     | 1 | allow `curl` via clj `(sh …)` when every URL is loopback (`localhost`, `127.0.0.1`, `[::1]`) and the flags are allowlisted (`-s -S -f -L -v -i -I -k -g`, `-m N`, `-X <METHOD>`, `-o /dev/null`); file-touching flags, `-H`, proxies and other hosts still ask |
| `sh-confirm`         | 1 | ask on any other clj `(sh …)` CLI |
| `clj-sh`             | → | composite: `repository-scripts` `localhost-curl` `sh-read-only` `sh-confirm` |

## `:match` fields

All present fields are **ANDed**; an absent field is no constraint.

| Field         | Matches                                                              |
|---------------|---------------------------------------------------------------------|
| `:tool`       | tool kind — keyword or set: `:write :edit :read :grep :find :ls :bash :clj :sh :bb :net :browser :mcp :other` (`:bb` = the bb tool; its `:command` is the `bb <task> <args…>` line it runs. `:net` = a user extension's network request, `:browser` = its headless-Chrome visit) |
| `:extension`  | the user extension behind an `xi.api.*` call — `true` (any), or string (exact / glob), regex, set. Agent tool calls carry none, so an `:extension` rule never matches them |
| `:extension-data` | own-data-dir predicate (opt-in) — `:own` matches when the target path resolves inside the requesting extension's data dir (`$XDG_DATA_HOME/xi/extensions/<id>`); symlinks are canonicalized |
| `:host`       | `:net` / `:browser` request host — string (exact / glob), regex, set |
| `:tool-name`  | raw tool name — **string** (exact / glob full match), **regex** (`re-find`), or **set** (membership). Targets one extension tool, which otherwise only has the kind `:other` (e.g. `"spawn_subagent"`). Synthetic clj `:sh` requests carry no tool name, so they never match. |
| `:path`       | target file path — **regex** (`re-find`, partial) or **glob string** (full match: `*`=one segment, `**`=any, `?`=one char). Matched against the raw arg **and** its resolved absolute path **and** the resolved path with a leading `$HOME` collapsed to `~` — so a pattern works whether the path was given absolute, relative, or `~`-prefixed, and may itself be written with `~`. Symlinks are canonicalized. |
| `:command`    | bash command / clj code / clj shell-out command — **regex** (`re-find`) or **string** (substring) |
| `:cli`        | shell-out binary (first token of a `clj` `(sh …)` / background command) — **string** (exact), **set** (membership), or **regex** (`re-find`); only `:sh` requests carry `:cli` |
| `:repo`       | git-root path **suffix** of the target/effective cwd (e.g. `"config/dotfiles"`) |
| `:dir`        | absolute **path prefix** of the effective cwd (`~` expanded)         |
| `:mcp-server` | MCP server id (for `mcp__<server>__<tool>` calls) — string/glob     |
| `:mcp-tool`   | MCP tool name — string/glob (`"*"` = any)                            |
| `:when`       | submap predicate over room ext state; a map value matches recursively (nested submap, ignoring extra keys), e.g. `{:plan-mode {:enabled? true}}`. Rooms of a named agent (`--agent ID`) carry `{:agent {:id "ID"}}`, so `{:agent {:id "root"}}` scopes a rule to that agent and `{:agent {}}` to any agent room |
| `:node`       | tree-sitter AST predicate (opt-in) — `{:type … :name … :contains …}` |
| `:outside`    | location predicate (opt-in) — `:cwd` matches when the target path resolves outside the effective cwd (and tmp); symlinks are canonicalized |
| `:credential` | credential-path predicate (opt-in) — `:read` matches when the target path resolves inside a hidden credential dir (`.ssh`, `.gnupg`, `.password-store`, …); symlinks are canonicalized |
| `:xi-rules-file` | xi-rules-file predicate (opt-in) — `true` matches when a `write`/`edit`/`bash`/`clj` call would change an xi rules file: a `rules.edn` whose content carries `:version` (existing, or introduced by the write/edit). Symlinks are canonicalized |
| `:chained`    | shell-composition predicate for `bash` (opt-in) — `true` matches a command using pipes, `;`/`&&`/`&`, `$(…)`, backticks, several lines or a leading `VAR=` (quoted separators and redirections like `2>&1` don't count) |
| `:bb-trusted` | `bb.edn` trust predicate for `bb` tool calls (opt-in) — `true` / `false` matches by whether the nearest `bb.edn`'s sha256 is in the trust store (`~/.config/xi/ext/bb-trust.edn`, written by `/clj trust-bb`) |
| `:within`     | operand-location predicate for clj `:sh` shell-outs (opt-in) — `:repo` matches when the call is fully literal and every non-flag arg resolves strictly inside the effective git repo (not the root itself, not `.git/` or `.xi/`) or tmp; flags must be bare short clusters (`-p`, `-rv`) — any `--long`/`--`/glued non-letter value never matches; symlinks are canonicalized |
| `:tracked`    | git-tracked predicate for clj `:sh` shell-outs (opt-in) — `:git` matches when the call is fully literal and every non-flag arg resolves inside the effective git repo to **tracked content**: a file in the index, or a directory holding at least one indexed file and nothing untracked (ignored files count as untracked). Deleting or moving such a path is recoverable from git (uncommitted edits to a tracked file are not — tracked means "in the index"). Same flag discipline as `:within`; the repo root, `.git/`, tmp and anything outside never match; symlinks are canonicalized. Checked with `git ls-files`, lazily — git is spawned only for a command whose other match fields (tool, `:cli`, …) already matched. Used by the `repository-scripts` default; the clj builtin `(rm dir)` consults it too — see [clj-tool.md](clj-tool.md#sh--permissions) |

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
without `:command` pre-approves that binary (skips its confirm); a `:sh` `:deny` blocks the eval
with the rule message. A `:sh` `:allow` that carries a `:command` grants only
the exact, fully-literal commands it matched, never the binary at large (same
for a `:within` or `:tracked` allow). If
another call to the same CLI in the eval isn't matched, or has a dynamic arg,
the CLI still needs approval (see
[clj-tool.md](clj-tool.md#sh--permissions)). `:repo` rules match against the git
root of the command's effective cwd. Session grants from `/clj allow <cli>` (and `:always`
answers) are stored exactly as `{:tool :sh :cli "<cli>"} → :allow` session
rules, so they appear in `/rules`. Read-only auto-run CLIs (`ls`, `cat`, `git`,
`rm`, `ss`, …) keep running through the clj tool's own autorun/escalation path
and are not short-circuited by a `:sh` `:allow`.

## `:action` types

| `:type`  | Effect                                                                  |
|----------|-------------------------------------------------------------------------|
| `:allow` | The call runs. Rules are first-match-wins, so an allow ends the decision: no later rule can ask about or deny it. (The `clj` tool's own per-command approvals still run, because they are part of that tool.) |
| `:deny`  | Block with an error result (`:message` shown to the agent).             |
| `:nudge` | Block with a **non-error** steering result — `:message` redirects the agent without signalling failure. |
| `:ask`   | Raise a confirm dialog. When no `:message` is given, an informative default is built from the request (MCP server/tool/arguments, else the bash/clj command, else the target path). `:options` defaults to `[:yes :no :always]`; answering `:always` persists a session allow-rule for the same call (narrowed to the MCP server + tool for MCP calls, and to the `:tool-name` for other extension tools), and `:repo` (the `[r]` choice, offered only for a `write`/`edit` whose target is in a git repo) persists one allowing writes and edits anywhere in that repo. For `write`/`edit` calls the dialog also previews the change as a diff (computed without writing; web and TUI). The TUI preview is capped at 30 lines. Press `d` to open the whole change in a scrollable view inside the dialog: `j`/`k` scroll, `space`/`b` page, `g`/`G` jump to the start or end, and `d`/`q`/`Esc` go back. `y`/`n`/`Enter` still answer from that view. `:unanswered :deny` refuses the call when no client can be asked (the default lets an unanswerable tool-call ask pass). |

An entry in `:options` may also be a map, `{:value … :key … :label …
:resolved-label … :event {:type …}}`: picking it approves the call and
dispatches the event (with `:room-id` and `:cwd` added) instead of saving a
session rule. Only events on a fixed allowlist are dispatched (currently
`:ext.clj/trust-bb`, used by the `bb-trust` default), so a rules file can't
turn a dialog click into an arbitrary event.

```clojure
{:match {:tool :bash :command #"\bgit push\b"}
 :action {:type :ask :message "Push to remote?" :options [:yes :no :always]}}
```

## Deciding without a tool call (`decide!`)

`xi.ext.rules/decide!` runs the same engine on any decision request: the
immutable hard-block, then the first matching rule, with `:ask` dialogs and
`[a]lways` / `[r]` persistence. It resolves to a decision (`:allow`,
`:approved`, `:pass`, `:unanswered`, `:deny`, `:nudge`). The tool-call policy
step (`xi.ext.rules/tool-policy`, wired in front of every tool call by
`xi.cli`) is a thin mapping over it. It is the only policy step: extensions
have no hook to allow, block or rewrite tool calls. Callers that aren't tool calls build their request
with `xi.rules.store/request`. The `xi.api.fs` / `.sh` / `.http` capabilities
of user extensions work this way, with every request tagged
`:extension <id>`. For them `:unanswered` (no one to ask) is a refusal, and
the dialog text starts with `[extension <id>]`.

## Managing rules at runtime

- `/rules` or `/rules list` — show the effective ruleset in precedence order.
- `/rules reload` — clear the file cache (files are otherwise mtime-cached).

Runtime rules are added programmatically (e.g. the `:always` answer above, or
the recommend-a-rule flow) via the `:ext.rules/add` event with `:scope :session`
or `:scope :server`.

Saving a recommended rule at `repo` / `global` scope prepends it to that file's
`:rules` (creating the file at the current `:version` if needed, keeping its
`:defaults` as written). An invalid file is left untouched and the save
reports why.

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
   (`repo`/`global`) appended to the on-disk rules file — **not through a tool
   call**, so the immutable hard-block is never involved. Re-run the
   original call to pick up the new rule.

Regex literals (`#"…"`) in a recommended or file rule are parsed with the full
Clojure reader, so they round-trip through save/load correctly.

## The immutable hard-block

Agents can **never** write the rules files
(`~/.config/xi/rules.edn`, `<repo>/.xi/rules.edn`) — not via `write`/`edit`, and
not via a `bash`/`clj` command that both references a rules file and contains a
write token (`>`, `tee`, `sed -i`, `cp`, `mv`, `dd`, `chmod`, `spit`, `writeFileSync`,
`rm`, …). This check runs first and cannot be overridden by any `:allow` rule.
Edit the rules files yourself, or use the recommend-a-rule flow on a guard
dialog.
