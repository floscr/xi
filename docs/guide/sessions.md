# Sessions

Every chat is saved as you go. This page covers continuing one, starting
over, and the tools that keep a long chat workable: going back to an earlier
point, summarising, trimming, and rolling over with pointers to the past.

## Continuing and starting over

| Command | Does |
| --- | --- |
| `/resume` | Pick a saved chat to continue. The terminal lists chats from this directory; the web client lists all of them. |
| `/new` (`Alt+n`) | A new chat in the same directory. |
| `/clear` | Empty the current chat and start fresh in it. |
| `/fork` | Continue in a new chat; the current one stays as it is. |
| `/favorite` | Mark the chat; `/favorites` lists marked ones. |
| `/sessions` | List saved chats. From a shell, `xi sessions`. |

A chat remembers its directory, model and name. Xi names a chat from its
first message; rename it from the web client's chat menu.

Resuming from the command line: `xi --session <id>`.

## Going back: `/tree`

`/tree` opens a selector over the chat's history. Pick an earlier message and
the chat continues from there, in a new direction. Nothing on disk is lost:
the old transcript stays, the chat just stops following it.

| Key | Does |
| --- | --- |
| Up / Down | Move |
| Type | Filter messages |
| Tab | Cycle what is listed: your messages, yours and the agent's, everything |
| Enter | Continue from this point |
| Ctrl+Enter | On one of your messages: go back to just before it and put its text in the editor, to send a changed version |
| Esc | Close |

The next message starts a fresh model session with the kept history in
front of it.

## Making room: `/truncate`

A long chat fills the model's context. `/truncate` summarises the
conversation so far and continues in a new chat with the summary as its
first message:

```text
/truncate              summarise everything
/truncate auth flow    summarise with the focus on "auth flow"
```

The summary keeps file paths, decisions and why, what is done and what is
pending, errors and how they were resolved. The old conversation stays
visible above a divider, and resuming the new chat later shows it again; it
is only hidden from the model.

The summary is written by a cheaper model, so it costs little.

## Keeping the detail: `/trim`, `/rollover`, `/lineage`

A summary is lossy. These three keep every detail recoverable: trimmed text
and earlier chats are cited by file, and the agent reads them back with its
normal tools only when it needs them.

**`/trim`** cuts large tool results out of the model's transcript, in place,
keeping the same chat:

```text
/trim                 preview with the defaults; changes nothing
/trim yes             apply the preview
/trim cancel          drop it
```

Options go in any order: a number is the size above which a result is
trimmed (default 500 characters); `read,bash` limits it to those tools;
`-20` also trims long agent messages except the last 20. Applying writes a
backup next to the transcript and replaces each cut with a placeholder that
names the backup and line. What you see on screen does not change; only
what the model sees shrinks, from the next message on.

**`/rollover`** starts a new chat whose first message lists every earlier
chat in the chain with its transcript path:

```text
/rollover                      pointers only, no model call
/rollover the auth refactor    plus a summary focused on that
```

**`/lineage`** prints that chain for the current chat.

`/truncate` and `/rollover` chain the same way, so they mix.

## Where chats are stored

| Path | What |
| --- | --- |
| `~/.config/xi/sessions/` | One file per chat: name, directory, model, links to earlier chats |
| `~/.claude/projects/<dir>/<id>.jsonl` | The transcript, kept by the Claude CLI |
| `~/.config/xi/personal-agent/<id>/` | Chats of an [agent profile](agents.md) |

The agent may read these directories, so it can search earlier chats when
asked.
