# Rules reference

Every match field, action, and built-in bundle of the rules engine. The
[introduction](rules.md) explains how to use them.

## A rules file

```clojure
{:type     :xi/rules      ; required
 :version  1              ; required
 :rules    [ … ]          ; your rules, first match wins
 :defaults [ … ]}         ; optional: which built-in bundles to keep
```

`~/.config/xi/rules.edn` applies everywhere; `<repo>/.xi/rules.edn` applies
inside that repository and wins over the global file. `:on-block` / `:do`
are accepted aliases for `:match` / `:action`.

An invalid file (wrong `:type`, missing or unsupported `:version`, unknown
top-level key, a bare vector, an unknown `:defaults` alias, unreadable EDN)
is replaced by one rule that denies everything, with a message naming the
file and the problem. `/rules` shows it.

## Order

1. Immutable: the agent can never write a rules file, by any tool.
2. Hardened tier (below). Not overridable; `--no-hardened-rules` at launch is
   the only way off.
3. `<repo>/.xi/rules.edn`
4. `~/.config/xi/rules.edn`
5. Rules added during a session on the server (process-wide)
6. Rules added during a chat (`a` and `r` answers, `/clj allow`)
7. The default bundles

## `:match`

Every field present must hold. An absent field is no constraint.

| Field | Matches | Value |
| --- | --- | --- |
| `:tool` | The kind of call | `:write` `:edit` `:read` `:grep` `:find` `:ls` `:bash` `:clj` `:sh` `:bb` `:net` `:mcp` `:other`, or a set. `:sh` is one program run from the clj tool; `:bb` is the bb tool; `:net` is an extension's request. |
| `:tool-name` | One tool by name | String (exact or glob), regex, or set. For extension tools, which are otherwise only `:other`. |
| `:path` | The file a call targets | Regex (partial match) or glob string (full match: `*` one segment, `**` any, `?` one char). Tested against the argument as given, the resolved absolute path, and that path with `$HOME` as `~`. Symlinks are resolved. |
| `:command` | The command line, or the clj code | Regex (partial) or substring. |
| `:cli` | The program of a clj `(sh …)` call | String (exact), set, or regex. |
| `:repo` | The git repository of the target or working directory | The end of its path, e.g. `"code/my-app"`. |
| `:dir` | The working directory | Absolute prefix; `~` is expanded. |
| `:extension` | A call made by an extension | `true` for any, or its id as string, glob, regex or set. Agent calls never match. |
| `:extension-data` | `:own` | The target is inside the calling extension's data directory. |
| `:host` | The host of an extension's request | String, glob, regex or set. |
| `:mcp-server` | An MCP server id | String or glob. |
| `:mcp-tool` | An MCP tool name | String or glob; `"*"` for any. |
| `:mcp-trusted` | `true` / `false` | Whether the server is trusted right now. |
| `:when` | A sub-map of the chat's extension state | `{:plan-mode {:enabled? true}}`, `{:agent {:id "root"}}` for an agent profile's chats, `{:agent {}}` for any profile. |
| `:outside` | `:cwd` | The target resolves outside the working directory and the temp directory. |
| `:credential` | `:read` | The target is in a credential directory (`.ssh`, `.gnupg`, `.password-store`, …). |
| `:xi-rules-file` | `true` | The call would change an Xi rules file. |
| `:chained` | `true` | A `bash` command with pipes, `;`, `&&`, `$(…)`, backticks, several lines or a leading `VAR=`. |
| `:bb-trusted` | `true` / `false` | Whether the nearest `bb.edn` is in the trust store. |
| `:within` | `:repo` | Every operand of a clj `(sh …)` call is a literal path inside the repository (not its root, `.git/` or `.xi/`) or the temp directory. |
| `:tracked` | `:git` | Every operand is git-tracked content inside the repository, so deleting or moving it is recoverable. |
| `:node` | `{:type … :name … :contains …}` | Tree-sitter match on the code an `edit` touches or a `write` creates; needs an installed grammar, otherwise never matches. |

For clj `(sh …)` calls, `:repo`, `:dir` and `:node` use the call's `:dir`
option when it has one, else the chat's directory.

## `:action`

| `:type` | Does |
| --- | --- |
| `:allow` | Runs the call. No later rule is consulted. For a clj `(sh …)` rule: without `:command`, `:within` or `:tracked` it allows the program; with one of them it allows only the exact matched command. |
| `:deny` | Blocks it. The agent sees `:message`. |
| `:nudge` | Blocks it, but reports `:message` as a hint rather than an error. |
| `:ask` | Shows a dialog. `:message` replaces the default text. `:options` defaults to `[:yes :no :always]`; `:unanswered :deny` refuses the call when nobody can answer instead of letting it through. |

Dialog answers: `:yes` and `:no` decide this call; `:always` saves a rule for
the chat matching the same thing (narrowed to the MCP server and tool, or the
tool name). The rule is stored with the chat (`<session-id>.ext.edn` next to
its metadata in `~/.config/xi/sessions/`), so it survives a server restart and a
closed room, and applies again when the chat is resumed. `:repo`, offered for
writes inside a git repository, saves a rule allowing writes and edits anywhere
in it. An edit's dialog previews the diff.

## The hardened tier

Always on unless Xi is started with `--no-hardened-rules`:

- `sudo`: denied.
- `scp`, `rsync`, `sftp`: denied, as programs and inside `bash` lines.
- A shell as the program of a clj `(sh …)` call (`bash`, `sh`, `zsh`, …):
  denied; commands go argv-style, one per call.
- Reading a private key under `~/.ssh/`: denied, through the read tools and
  through command lines that name one. `*.pub`, `config`, `known_hosts`,
  `authorized_keys` stay readable.
- Changing an Xi rules file anywhere else (one carrying `:version`): asks
  every time, yes or no only.

## Default bundles

The default tier, in order. `:defaults` in a rules file replaces the whole
list; inline rule maps may be mixed in.

| Bundle | Does |
| --- | --- |
| `tmp-cleanup` | Nudge: `rm` under `/tmp` is unnecessary. |
| `no-auto-memory` | Nudge: writes into a Claude auto-memory directory are skipped. |
| `extension-credentials` | Deny: extensions reading or writing credential paths. |
| `extension-data` | Allow: extensions in their own data directory. |
| `xi-sessions` | Allow: reads under `~/.config/xi/sessions`. |
| `claude-sessions` | Allow: reads under `~/.claude/projects`. |
| `plan-mode` | While `/plan` is on: allow `tasks/todo.md`, deny other writes and mutating commands. |
| `write-gates` | Ask: writes into mail or key directories (`sensitive-writes`); into `.env`, `.git/`, `node_modules/` (`protected-writes`); outside the working directory, with the `r` option (`outside-writes`). |
| `bash-chained` | Deny: composed `bash` commands; use the clj tool. |
| `bb-trust` | Ask before `bb` runs with an untrusted `bb.edn`; `a` trusts the file. |
| `bash-guards` | Deny remote shells; ask on destructive patterns (`rm -rf`, `chmod -R`, `git push`, `kill`, …), also in bb task lines. |
| `server-control` | Ask on `bb serve:restart` / `serve:stop`; run detached once approved. |
| `mcp-confirm` | Ask on a call to an untrusted MCP server; `a` trusts it. |
| `subagent-confirm` | Ask on every `spawn_subagent`. |
| `extension-sh` | Ask on every program an extension runs. |
| `net-confirm` | Ask on every host an extension requests. |
| `script-exec` | Ask before an interpreter runs inline code or a script (`bb -e`, `bb -f`, `node -e`, `python x.py`, `bun x.ts`, `clojure -M`, …); no `a`, refused when nobody can answer. `bb <task>`, `bun test`, `--version` and the like are free. |
| `clj-sh` | For clj `(sh …)`: allow read-only `sed -n …p`, in-repo `mv` `cp` `mkdir` `touch` `rmdir`, and `rm` of git-tracked content (`repository-scripts`); allow `curl` to loopback with safe flags (`localhost-curl`); allow read-only programs (`sh-read-only`); ask for everything else (`sh-confirm`). |

Names are written `:xi.rules.defaults/<bundle>` in `:defaults`:

```clojure
:defaults [:xi.rules.defaults/plan-mode
           :xi.rules.defaults/write-gates
           {:match {:tool :sh :cli "git" :command #"\bgit push\b"}
            :action {:type :ask :message "Push?"}}
           :xi.rules.defaults/clj-sh]
```

Keep `plan-mode` before the write gates and the nudges first, as the
built-in order does. `:defaults []` turns the tier off.

## Managing rules while Xi runs

- `/rules` lists the rules in effect, in order, with their source.
- `/rules reload` re-reads the files (they are otherwise cached by
  modification time).
- `/allow`, `/allow always`, `/allow repo`, `/deny` answer the pending
  dialog.
- `/clj allow <cli>` and `/clj revoke <cli>` add and remove a chat rule for
  a program.

## Extensions and rules

Calls an [extension](extensions.md) makes through `xi.api` are rules
requests tagged with `:extension <id>`. The same fields apply; `:extension`,
`:host` and `:extension-data` exist for them. When a rule asks and no dialog
can be shown (an effect, a one-shot run), the call is refused.
