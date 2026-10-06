# Slash commands

Type `/` and a name into the chat to run a command instead of sending a
message. `/help` lists everything that is available in the current setup.

## Everyday

| Command | Does |
| --- | --- |
| `/new` | Start a new chat in the same directory (also `Alt+n`) |
| `/resume` | Pick a previous chat to continue |
| `/sessions` | List previous chats |
| `/favorite` | Mark the current chat as a favourite; `/favorites` lists them |
| `/cd <dir>` | Change the working directory of this chat |
| `/project` | Pick one of your [projects](projects.md) (also `Alt+p`) |
| `/model [name]` | Show or switch the model |
| `/fork` | Continue the conversation in a new chat, keeping this one as it is |
| `/truncate` | Summarise the conversation so far to make room for more |
| `/quit` | Leave Xi |

## Permissions

| Command | Does |
| --- | --- |
| `/allow` (`/a`) | Allow the pending request (also `Alt+a`) |
| `/allow always` | Allow it and stop asking for the same thing this session |
| `/allow repo` | Allow writes anywhere in that repository |
| `/deny` (`/d`) | Deny it (also `Alt+d`) |
| `/rules` | Show the rules in effect, in order; `/rules reload` re-reads the files |
| `/plan` | Toggle plan mode: the agent may read and write a plan, nothing else |

## Reviewing work

| Command | Does |
| --- | --- |
| `/diff` | Show what this chat changed. `/diff staged`, `/diff unstaged`, `/diff <ref>` show git diffs |
| `/commit` | Stage and commit with the agent's help |
| `/worktree` | Move the chat into a fresh git worktree; `merge`, `list`, `remove` |
| `/buffers` | Switch between the chat, the log, the system prompt and the diff view |
| `/events` | Show the event log of this chat |
| `/prompt` | Show the system prompt the agent gets |

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

## Reference

The full list, with every argument, is in
[the commands reference](../commands.md).
