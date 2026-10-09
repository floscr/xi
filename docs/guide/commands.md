# Slash commands

Type `/` and a name into the chat to run a command instead of sending a
message. `/help` lists everything that is available in the current setup.

## Everyday

| Command | Does |
| --- | --- |
| `/new` | Start a new chat in the same directory (also `Alt+n`) |
| `/resume` | Pick a previous chat to continue |
| `/sessions` | List previous chats |
| `/cd <dir>` | Change the working directory of this chat |
| `/project` | Pick one of your [projects](projects.md) (also `Alt+p`) |
| `/model [name]` | Show or switch the [model](models.md) |
| `/fork` | Continue the conversation in a new chat, keeping this one as it is |
| `/tree` | Go back to an earlier point of the chat and continue from there |
| `/truncate` | Summarise the conversation so far to make room for more |
| `/trim`, `/rollover`, `/lineage` | Shrink the model's context while keeping every detail recoverable; see [Sessions](sessions.md) |
| `/summary` | Describe what this chat is about |
| `/clear` | Empty the chat |
| `/reload` | Restart Xi |
| `/quit` | Leave Xi |

## Permissions

| Command | Does |
| --- | --- |
| `/allow` (`/a`) | Allow the pending request (also `Alt+a`) |
| `/allow always` | Allow it and stop asking for the same thing this session |
| `/allow repo` | Allow writes anywhere in that repository |
| `/allow block` | Allow this request and the rest of the tool call's requests (offered when one call asks several times) |
| `/deny` (`/d`) | Deny it (also `Alt+d`). `/deny <reason>` tells the agent why |
| `/rules` | Show the rules in effect, in order; `/rules reload` re-reads the files; `/rules save` moves this chat's rules into `<repo>/.xi/rules.edn` |
| `/plan` | Toggle plan mode: the agent may read and write a plan, nothing else |

## Reviewing work

| Command | Does |
| --- | --- |
| `/diff` | Show what this chat changed. `/diff staged`, `/diff unstaged`, `/diff <ref>` show git diffs |
| `/commit` | Stage and commit with the agent's help |
| `/worktree` | Move the chat into a fresh git worktree; `merge`, `list`, `remove` |
| `/buffers` | Switch between the chat, the log and the chat's open buffers (diffs, files, the system prompt); the last entry closes them all |
| `/events` | Show the event log of this chat |
| `/prompt` | Show the system prompt the agent gets |
| `/holds`, `/release` | Show who holds the repository's git index when two chats share it; force it free |
| `/ps`, `/kill <pid>` | Background processes the agent started; stop one |
| `/subagents` | Background sub-agents of this chat |
| `/debug` | Copy debugging details to the clipboard |

## Extending

| Command | Does |
| --- | --- |
| `/ext list` | Which extensions are loaded; `/ext enable` and `/ext disable` toggle one |
| `/ext reload` | Re-read your [extensions](extensions.md) without a restart |
| `/mcp list` | Which [MCP servers](mcp-servers.md) are registered; `/mcp add` adds one |
| `/clj` | The [clj tool](clj-tool.md)'s allowed commands; `/clj allow <cli>` adds one |
| `/skill list` | Skills found in the project; `/skill load <name>` adds one to the prompt |

## Commands from extensions

An extension adds a command with a `:commands` entry; it then shows up in
`/help` and in the terminal's completion like a built-in one. See
[Tutorial: a command and a key](extension-tutorial-command.md).

In the terminal, the names complete as you type. Built-in commands win over
an extension's command of the same name.
