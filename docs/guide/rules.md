# Permissions and rules

Rules decide what the agent may do without asking you. They are a list in a
file: each rule matches a kind of tool call and says allow, deny, ask, or
nudge. The first rule that matches wins. A fifth kind, a hint, decides
nothing and only adds text to whatever rule wins below it.

## What happens without any rules

Xi ships with defaults that cover the usual cases. Out of the box:

- Reading, searching and listing inside the project runs freely.
- Writing inside the project runs freely. Writing outside it, or into `.env`,
  `.git/`, `node_modules/` or a mail or key directory, **asks**.
- Read-only commands (`ls`, `cat`, `git`, `grep`, …) run freely. Every other
  command **asks** the first time; "always" allows it for the rest of the chat.
- A command whose program is not installed **fails at once** with an error
  naming it, with no dialog.
- Destructive commands (`rm -rf`, `git push`, `kill`, …) **ask** every time.
- Dumping the environment (`env`, `printenv`, `/proc/<pid>/environ`), reading
  a credential file (`.env`, `~/.aws/credentials`, …) or printing a secret
  (`pass`, `gh auth token`, …) **asks** every time, with a warning that the
  output goes to your LLM provider. Reading one variable is free.
- Tools from an MCP server **ask** until you trust the server.
- Starting a sub-agent **asks**.

Some things are never allowed, whatever your rules say: `sudo`, remote
shells and copies (`ssh`, `scp`, `rsync`), reading SSH private keys, and
writing to the rules files themselves. Changing `config.edn` always asks,
even when it is a symlink into a dotfiles checkout the agent may otherwise
edit. This is the **hardened tier**; only the person starting Xi can turn it
off, with a command-line flag, and the agent cannot.

## The dialog

When a call asks, both clients show the same dialog: what the tool wants to
do, and for an edit, the diff it would make.

| Key | Answer |
| --- | --- |
| `y` | Allow this call |
| `n` | Deny it |
| `a` | Allow it, and stop asking for the same thing in this chat |
| `r` | For writes: allow writes anywhere in that repository |
| `d` | For edits: open the whole diff |

`/allow`, `/deny`, `Alt+a` and `Alt+d` answer it from the keyboard. An answer
of `a` or `r` adds a rule that lives with the chat; `/rules` shows it.

To tell the agent why you said no, deny with a reason. In the web client,
click the `⋯` next to Deny: the message box becomes a reason field, and
Enter denies the call (Esc goes back without answering). In either client,
`/deny <reason>` does the same. The agent gets your reason in the tool result,
so it can change course instead of retrying.

## Writing your own rules

Put rules in `~/.config/xi/rules.edn`. They apply to every project.

```clojure
;; ~/.config/xi/rules.edn
{:type    :xi/rules
 :version 1
 :rules
 [;; reads anywhere under ~/code never ask
  {:match  {:tool #{:read :grep :find :ls} :path #"^~/code(?:/|$)"}
   :action {:type :allow}}

  ;; steer away from shell scripts
  {:match  {:tool #{:write :edit} :path #"\.sh$"}
   :action {:type :nudge :message "Write Babashka scripts, not shell scripts."}}

  ;; deleting under /tmp never asks (every path the command names must
  ;; be under /tmp — the glob is tested against each one)
  {:match  {:tool :sh :cli "rm" :path "/tmp/**"}
   :action {:type :allow}}

  ;; always confirm a push
  {:match  {:tool :sh :cli "git" :command #"\bgit push\b"}
   :action {:type :ask :message "Push to the remote?"}}]}
```

A rule has a `:match` and an `:action`. Every key in `:match` must hold for the
rule to apply; a key you leave out is no constraint.

### Matching

| Key | Matches | Value |
| --- | --- | --- |
| `:tool` | The kind of call | `:read` `:write` `:edit` `:grep` `:find` `:ls` `:sh` `:bb` `:clj` `:net` `:mcp` `:other`, or a set of them |
| `:path` | The file a call targets, or every file a `(sh …)` command names | A regex (`#"…"`, partial match) or a glob string (`"src/**/*.cljs"`, full match; `**/` is zero or more directories). Tested against the path as given, resolved, and with `$HOME` shown as `~`. For a command, each non-flag argument must match |
| `:cli` | The program a command runs | A string, a set, or a regex |
| `:read-only` | Whether a `(sh …)` call's arguments are read-only for its program (`find` without `-exec`, `git` without `push` or `-c`, …) | `true` or `false`; see the [reference](rules-reference.md#match) |
| `:command` | The command line | A regex or a substring |
| `:repo` | The git repository | The end of its path, like `"code/my-app"` |
| `:dir` | The working directory | A path prefix |
| `:extension` | Calls made by an [extension](extensions.md) | Its id as a string |
| `:host` | The host an extension requests, or every host a read-only `(sh "curl" …)` requests | A string, set or regex |
| `:mcp-server` / `:mcp-tool` | An MCP server and its tool | Strings or globs |
| `:tool-name` | One particular tool by name | A string, set or regex |
| `:user` | Who the call acts for | A user id (string, set or regex), or a map matched against their `config.edn` profile, like `{:meta {:team "ops"}}` |

### Actions

| `:type` | Does |
| --- | --- |
| `:allow` | Runs the call. No later rule is consulted. |
| `:deny` | Blocks it; the agent sees `:message`. |
| `:ask` | Shows the dialog. `:message` replaces the default text. |
| `:nudge` | Blocks it, but tells the agent `:message` as a hint rather than an error. |
| `:hint` | Decides nothing. Its `:message` is added under the message of the rule that wins below it, so a note of yours rides on a built-in deny or ask. |

`:message` can use `{cli}` for the program and `{command}` for the command
line. Hints are how you teach the agent what to do instead on your machine
without rewriting the built-in rule. If `python3` is not installed, the
default rule already denies it with an error; a hint adds the alternative:

```clojure
{:match  {:tool :sh :command #"-m\s+http\.server"}
 :action {:type :hint
          :message "No python3 here. Serve a directory with babashka:
  (process/start \"bb -m babashka.http-server --port 8000 --dir public\")"}}
```

### Order and precedence

Rules are tried in this order, and the first match wins:

1. The hardened tier (built in, cannot be overridden)
2. `<project>/.xi/rules.edn`
3. `~/.config/xi/rules.edn`
4. Rules added during a chat (`a` and `r` answers, `/clj allow`)
5. The built-in defaults

Your files sit above the in-chat grants on purpose: a rule you wrote down
beats an "always" clicked in a hurry. The project's file beats the global one.

Within a file, order matters too. Put the specific rule before the general
one.

## Rules for some users

On a shared server, `:user` scopes a rule to the people it is for. A call acts
for whoever sent the chat's latest prompt (see [Users](server.md#users)). Name
them by id, or by what `config.edn` says about them under `:users`:

```clojure
;; config.edn
:users {"alice" {:name "Alice" :meta {:team "guests"}}
        "bob"   {:meta {:roles ["admin"]}}}

;; rules.edn
[;; alice may not run commands at all
 {:match  {:user "alice" :tool #{:bash :sh :clj :bb}}
  :action {:type :deny :message "Commands are off for this account."}}

 ;; every guest is asked before any write
 {:match  {:user {:meta {:team "guests"}} :tool #{:write :edit}}
  :action {:type :ask}}

 ;; admins push without asking
 {:match  {:user {:meta {:roles "admin"}} :tool :sh :command #"\bgit push\b"}
  :action {:type :allow}}]
```

A user with no matching rule falls through to the rest of the list, as usual.
An id nobody declared has an empty `:meta`. The profile is read when a rule
needs it, so an edit to `config.edn` applies on the next call. If `config.edn`
is invalid, a `:meta` rule that restricts applies to everyone and one that
allows applies to no one, until you fix it.

A user is whoever their device says it is, unless you pin the device to a
user with `xi clients user` (see [Users](server.md#users)). Do that for
every device before you rely on a rule that restricts someone.
[Tutorial: users and roles](extension-tutorial-roles.md) sets up roles end
to end, including an extension that grants its own.

## Rules for one project

A repository can carry its own rules in `.xi/rules.edn` at its root, with the
same shape. They apply when the chat runs inside that repository and win over
the global file.

`/rules save` (also in the `Ctrl`/`Cmd+K` palette) moves the rules you
granted in the current chat into that file.

## Changing the defaults

A `:defaults` key replaces the built-in set with the bundles you list. Leave
one out to switch it off:

```clojure
{:type     :xi/rules
 :version  1
 :rules    []
 :defaults [:xi.rules.defaults/plan-mode
            :xi.rules.defaults/write-gates
            :xi.rules.defaults/bash-guards
            :xi.rules.defaults/mcp-confirm
            :xi.rules.defaults/clj-sh]}
```

`/rules` shows the full default list with its bundle names.

## Reference

Every match field, action option, and default bundle:
[Rules reference](rules-reference.md).
