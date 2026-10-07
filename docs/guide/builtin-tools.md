# Built-in tools

The tools the agent has out of the box, what each one does, and when it asks
you first. Everything here ships with Xi; [extensions](extensions.md) and
[MCP servers](mcp-servers.md) add to the list, and an
[agent profile](agents.md) can shorten it.

Every call passes the [rules](rules.md) before it runs. The "Asks" column
describes the built-in defaults; your own rules can change any of it.

## Files

| Tool | Does | Asks |
| --- | --- | --- |
| `read` | A file's contents. `offset` and `limit` read a line range (`offset` counts from 0). A large source file comes back as an outline instead; see [How the agent reads code](reading-code.md). The result ends in a `[file-hash: …]` token. | Never. Private SSH keys are always refused. |
| `read_source` | The code behind an outline entry: one definition by name, a 1-based line range, or the whole file. | Never |
| `write` | Create or overwrite a file with the given content. Parent directories are created. | Outside the project, or in `.env`, `.git/`, `node_modules/`, mail and key directories |
| `edit` | Replace exact text in a file, one or more replacements per call, each applied against the original file. With `expectedHash` set to the token from the last read, the edit is rejected if the file changed in between, so two chats editing the same file cannot overwrite each other. | As `write`; the dialog shows the diff |
| `ls` | Entries of a directory with their type: `d` directory, `f` file, `l` symlink. | Never |
| `find` | File paths matching a name glob, via `fd`. | Never |
| `grep` | Lines matching a regular expression, via ripgrep, with path and line number. `glob` restricts the files. | Never |
| `view_image` | Shows the agent a PNG, JPEG, WebP or GIF so it can see it. Large images are scaled down. | Never |

While `/plan` is on, `write` and `edit` are refused except for the plan file.

## Running things

There is no shell tool. Instead the agent gets a sandboxed Clojure REPL with
helpers for files, programs and the network, so every command is one
argv-style call you can read before it runs.

| Tool | Does | Asks |
| --- | --- | --- |
| `clj` | Evaluate Clojure in the sandbox. The REPL persists for the chat. Programs run through `(sh "cmd" "arg" …)`, git through `(git …)`, HTTP through `(curl …)`, background processes through the `process` namespace. See [The clj tool](clj-tool.md) and the [reference](clj-tool-reference.md). | Any program that is not read-only, the first time; a path outside the project |
| `bb` | Run a task from the project's `bb.edn`, or list the tasks when no task is given. | Until the `bb.edn` is trusted with `/clj trust-bb` or once in the dialog, and again after `bb.edn` changes. Destructive task lines and `bb serve:restart` ask every time. |
| `sleep` | Pause for up to 60 seconds. Meant for pacing, not for waiting on a process. | Never |

A `bash` tool exists in Xi, but the clj tool hides it. It only comes back
when you disable the clj extension with `/ext disable clj`, and then it
refuses piped or chained commands.

## Git

The `/commit` command tells the agent to use these tools; the agent can also
call them on its own.

| Tool | Does | Asks |
| --- | --- | --- |
| `git_overview` | Changed files with line counts; `staged` shows the index instead. | Never |
| `git_file_diff` | The diff of the given files, `staged` or not. | Never |
| `git_hunk` | The hunks of one file's diff, numbered from 1. | Never |
| `git_stage_hunks` | Stage the given files, or everything. | Never |
| `git_commit` | Commit what is staged with a message; `files` stages those files first. | Never |

These tools share the repository's git index with every other chat in the
same repository; `/holds` shows who is using it. See
[Slash commands](commands.md#reviewing-work).

## Clojure code

For `.clj`, `.cljs` and `.cljc` files the agent has structural editing tools
that work on the syntax tree instead of text. They are offered in every
project; the agent is told to prefer them over `edit` for Clojure.

| Tool | Does |
| --- | --- |
| `clj_outline` | Every top-level form with its lines, type, name and arguments. Cheaper than reading the file. |
| `clj_tree` | Outline every source file of a project found through `deps.edn`, `project.clj` or `bb.edn`; `grep` searches across them. |
| `clj_deps` | What a form depends on, transitively, and which of those are leaves or circular. |
| `clj_topo` | The order of forms that needs no forward declarations. |
| `clj_replace` | Replace one complete form by another, matched by structure rather than text. |
| `clj_mv` | Move a form before another one in the same file. |
| `clj_extract` | Move forms into a new namespace, add the require, keep the order. Dry run unless `execute` is true. |
| `clj_fix_declares` | Remove `(declare …)` forms by reordering. Dry run unless `execute` is true. |
| `clj_rename_ns` | Rename a namespace prefix in every file. Dry run unless `execute` is true. |
| `clj_fix_parens` | Repair unbalanced brackets in a file. |

None of them asks by default, including the ones that write. They are not
`write` or `edit` calls, so the write gates do not see them. To ask before
one of them changes a file, match it by name:

```clojure
{:match  {:tool-name #{"clj_replace" "clj_mv" "clj_extract"
                       "clj_fix_declares" "clj_rename_ns" "clj_fix_parens"}}
 :action {:type :ask :message "Change Clojure code structurally?"}}
```

## Earlier chats

| Tool | Does | Asks |
| --- | --- | --- |
| `session_search` | Find previous chats whose title or messages contain a phrase, case-insensitive. Returns up to 20 chats, newest first, each with title, directory, date, id and a short excerpt around the match. Searches the current project's chats; `cwd` picks another project, `all` searches every project. | Never |

Ask the agent "what did we decide about the cache last week?" and it searches
instead of guessing. A found id can be resumed with `xi --session <id>`; see
[Sessions](sessions.md).

## Sub-agents

A sub-agent is a second agent that runs a task in its own context and reports
back, so the research or review it does never fills the main chat.

| Tool | Does | Asks |
| --- | --- | --- |
| `spawn_subagent` | Start a sub-agent with a task description and an optional label. Returns an id at once; the work runs in the background. The sub-agent starts with no context but the task. | Every time; `a` stops asking for this chat |
| `list_subagents` | The chat's sub-agents with status, running time and a line of recent output. | Never |
| `subagent_result` | The final output of a sub-agent, or a notice that it is still running. | Never |
| `stop_subagent` | Stop one by id. | Never |

`/subagents` shows them in the chat; the web client has a panel for them.
The `subagent-confirm` default rule is what asks; see
[Rules reference](rules-reference.md#default-bundles). When no client is
attached to answer, the spawn is refused.

## Review canvas

`/canvas-review` asks the agent to lay a diff out on a canvas in the web
client: code blocks, comments, connections and a walkthrough. The agent
builds it with these tools, and the canvas updates live on every client.

| Tool | Does |
| --- | --- |
| `canvas_review_add_block` | A code block for a changed region: file and line range. |
| `canvas_review_add_comment` | A short note, attached to a block. |
| `canvas_review_add_prose` | A longer Markdown note about the change as a whole. |
| `canvas_review_connect` | A labelled connection between two nodes. |
| `canvas_review_highlight` | Mark lines inside a block. |
| `canvas_review_set_plan` | The order in which to read the nodes; the Next and Prev buttons follow it. |

None of them asks.

## Xi itself

| Tool | Does | Asks |
| --- | --- | --- |
| `ext_reload` | Re-evaluate your [extensions](extensions.md), the same as `/ext reload`, and report which loaded and which failed with the error. | Never |
| `xi_events` | The chat's event log with timestamps and effects, the same as `/events`. Useful when an extension does not behave. | Never |

## Tools you may also see

Depending on your setup the agent lists more tools than these:

- Tools from your own [extensions](extensions.md), which run under the same
  rules as the built-in ones.
- Tools from [MCP servers](mcp-servers.md), which ask until the server is
  trusted.

The Claude SDK's own tools are never offered. Xi hands its tools to the SDK
over MCP, so the rules apply to every call whichever model runs.

## Limiting the list

An [agent profile](agents.md) names the tools the model gets, by name as
written here. A tool not in the list is not offered. A rule can also refuse a
tool outright, by its name:

```clojure
{:match  {:tool-name "spawn_subagent"}
 :action {:type :deny :message "No sub-agents in this setup."}}
```

Every tool result is capped in size. Output beyond the cap is cut with a note;
the `clj` tool saves long output to a file instead and names it in the result.
