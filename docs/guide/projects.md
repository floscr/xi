# Projects

Xi keeps a list of your project directories so you can jump into any of them
from a chat, and so each project can carry its own prompt and snippets.

A project is just a directory. You tell Xi where to look in
`~/.config/xi/config.edn`; Xi adds the rest as you work.

## The shortest setup

Create `~/.config/xi/config.edn` (or add the `:projects` key to the one you
have):

```clojure
{:type     :xi/config
 :version  1
 :projects {:browse ["~/code"]}}
```

Every directory directly inside `~/code` is now a project. Open the picker with
`/project` (or Alt+P) in the terminal client, or look at the projects page in
the web client. Picking a project in the terminal inserts its path into your
message; in the web client you start or resume a chat there.

No restart is needed after editing the file — Xi reads it each time it lists
projects.

## Where projects come from

Xi combines three sources into one list. Each directory appears once, and
directories that don't exist on this machine are skipped.

| Source | You set it with | What it is |
| --- | --- | --- |
| Single repos | `:repos` in the config | Directories listed exactly as you wrote them. |
| Browsed | `:browse` in the config | Directories Xi scans for repos. |
| Remembered | nothing — automatic | Git repos you worked in that neither of the above covers. |

The list is ordered by when you last used each project, most recent first.
Projects you have never opened keep the order above.

## Scanning for repos: `:browse`

`:browse` takes directories to look inside. A plain string looks one level
down; a map lets you go deeper:

```clojure
:projects {:browse ["~/code"
                    {:dir "~/work" :depth 3}]}
```

| Key | Default | Meaning |
| --- | --- | --- |
| `:dir` | — | The directory to scan. `~` works. |
| `:depth` | `1` | How many levels below `:dir` to look, from 1 to 6. `1` means only its direct children. |
| `:git?` | `true` | `true`: a directory is a project when it contains a `.git`. Xi stops at a repo and doesn't look inside it. `false`: every directory down to `:depth` is a project. |

With `{:dir "~/work" :depth 3}`, a repo at `~/work/client/api` is found, and so
is one at `~/work/tools`. Directories whose names start with a dot, and
`node_modules`, are never scanned.

Use `:git? false` when your projects aren't all repos and you want every folder
listed:

```clojure
{:dir "~/notes" :git? false}
```

## Listing single directories: `:repos`

For a directory that lives on its own, outside anything you scan, list it
directly:

```clojure
:projects {:repos ["~/.config/some-tool" "~/documents/journal"]}
```

These are listed whether or not they contain a `.git`.

## Remembered projects

You don't add projects by hand. Whenever you start a chat inside a git
repository, or change into one with `/cd`, Xi notes the repository. If neither
`:repos` nor `:browse` already covers it, it joins the list — so a one-off
checkout shows up the next time you open the picker, with nothing to configure.

Xi remembers the repository itself, not the folder you happened to be in: a
chat started in `my-app/src/components` remembers `my-app`. A directory that
isn't inside a repository is not remembered, and neither are your home
directory or `/`.

Xi keeps the 50 most recent remembered repositories; change that with
`:remember-limit`, and set it to `0` to turn remembering off:

```clojure
:projects {:remember-limit 20}
```

The same history is what orders the whole list by recent use, so it keeps being
recorded for the projects you configured even with remembering off.

It is saved by Xi itself in `~/.config/xi/state/projects.edn`, not in
`config.edn`. That keeps your config yours: Xi never edits it, so it can be
generated, read-only, or shared between machines. To keep a directory in the
list for good, add it to `:repos` or put it under a `:browse` directory;
to drop a remembered repository, see "Start over" below.

## Per-project prompt and snippets: `:settings`

A project can give Xi extra instructions and one-click prompts that apply only
when a chat runs in that exact directory. Key the entry by the project's path:

```clojure
:projects
{:settings
 {"~/code/my-app"
  {:agents-prompt  "docs/agent-notes.md"
   :agents-replace false
   :snippets [{:label "Run checks"
               :text  "Run the tests and report what fails."}]}}}
```

| Key | Meaning |
| --- | --- |
| `:agents-prompt` | Added to the system prompt after the project's own `AGENTS.md` files. If a file exists at that path — relative to the project directory first, then as an absolute path — its contents are used; otherwise the string itself is the prompt. |
| `:agents-replace` | `true`: the prompt takes the place of the project's own `AGENTS.md` (or `CLAUDE.md`) at its root. Files in parent directories still load. Default `false`. |
| `:snippets` | Prompts offered in the web client's snippets menu in that project only. Each has a `:label` (what you see) and `:text` (what gets inserted into your message). |

The match is on the exact directory. A chat started in `~/code/my-app/api`
doesn't pick up the settings of `~/code/my-app`; give that directory its own
entry if it should have them. Snippets you want everywhere belong in
`~/.config/xi/snippets.edn` instead.

The prompt is read when a chat starts (and when you `/cd` into the project), so
an edit applies to the next chat.

## A complete example

```clojure
{:type     :xi/config
 :version  1
 :projects {:browse   ["~/code"
                       {:dir "~/work" :depth 3}]
            :repos    ["~/.config/some-tool"]
            :remember-limit 20
            :settings {"~/code/my-app"
                       {:agents-prompt "docs/agent-notes.md"
                        :snippets [{:label "Run checks"
                                    :text  "Run the tests and report what fails."}]}}}}
```

## When something is off

**The list is empty.** Directories that don't exist are skipped without a
message, so a typo in a path looks like an empty list. Check the paths, and
that `:depth` is deep enough to reach your repos — with the default `:git?
true`, a directory without a `.git` isn't a project.

**Xi says the config is invalid.** The message names the problem, for example
`:projects :browse entries must be a dir string or {:dir d :depth 1..6 :git?
bool}`. Xi rejects the *whole* file when any part is invalid, so until it is
fixed your extensions and agent profiles from the same file are off too, and no
project settings apply. Fix the line it names.

**A project is missing from a scanned directory.** Hidden directories are
skipped, and with `:git? true` a folder without a `.git` is only searched
through, not listed. Raise `:depth`, or use `:git? false`.

**A directory I worked in is missing from the list.** Only git repositories are
remembered: a directory without a `.git` is never added. Put it in `:repos` if
you want it listed anyway.

**Start over.** Delete `~/.config/xi/state/projects.edn` to forget all
remembered repositories and the recent-use order. The config is untouched.

## Reference

Every key, default and limit is in [the configuration reference](../config.md#projects).
