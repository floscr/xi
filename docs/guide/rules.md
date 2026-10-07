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
| `:path` | The file a call targets | A regex (`#"…"`, partial match) or a glob string (`"src/**/*.cljs"`, full match). Tested against the path as given, resolved, and with `$HOME` shown as `~` |
| `:cli` | The program a command runs | A string, a set, or a regex |
| `:command` | The command line | A regex or a substring |
| `:repo` | The git repository | The end of its path, like `"code/my-app"` |
| `:dir` | The working directory | A path prefix |
| `:extension` | Calls made by an [extension](extensions.md) | Its id as a string |
| `:host` | The host an extension requests | A string, set or regex |
| `:mcp-server` / `:mcp-tool` | An MCP server and its tool | Strings or globs |
| `:tool-name` | One particular tool by name | A string, set or regex |

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

## Rules for one project

A repository can carry its own rules in `.xi/rules.edn` at its root, with the
same shape. They apply when the chat runs inside that repository and win over
the global file.

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

## When something is off

**Every call is denied with a message naming `rules.edn`.** The file is
invalid: a missing `:type` or `:version`, an unknown key, or EDN that does not
read. Xi replaces a broken file with one rule that denies everything, so a
typo can never silently drop your deny rules. Fix the line the message names.

**A rule does not match.** `:path` regexes match anywhere in the path; a glob
must match the whole path. Check `/rules` to see the order; an earlier rule
may be winning.

**The agent asks about something I already allowed with `a`.** In-chat grants
are below your files. A rule in `rules.edn` that asks for the same thing wins.
Write an `:allow` there instead.

**Xi refuses to let the agent edit `rules.edn`.** By design. Edit it yourself.

## Reference

Every match field, action option, and default bundle:
[Rules reference](rules-reference.md).
