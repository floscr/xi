# Project instructions

Xi reads a project's `AGENTS.md` into the system prompt at the start of every
chat, and tells the agent about every other `AGENTS.md` in the repository so
it reads the right one before it edits a subdirectory. Nothing has to be
configured; the files only have to exist.

## The shortest setup

Put an `AGENTS.md` at the root of the repository:

```markdown
# my-app

- Build with `npm run build`, test with `npm test`. Run the tests before you
  say a change is done.
- The API lives in `src/api/`, the web client in `src/web/`. Shared types are
  in `src/types/` and nowhere else.
- Never edit `src/generated/`; run `npm run codegen` instead.
```

Start Xi in the project. The header of the chat says how many files it
loaded, and `/prompt` shows the full system prompt the agent gets, with the
file's contents in it.

## Which files load

When a chat starts, Xi walks from the chat's directory up to the root of the
filesystem. In each directory it looks for `AGENTS.md`, and if there is none,
for `CLAUDE.md`. A directory holding both contributes only its `AGENTS.md`;
a `CLAUDE.md` there is usually just a pointer to it.

Every file found is read in full and added to the system prompt, outermost
first, innermost last. Each one is introduced by a heading with its path
relative to the chat's directory, so the agent can tell which project a rule
belongs to.

This means a file above the project applies to everything below it. A
`~/code/AGENTS.md` with your personal conventions is loaded for every project
under `~/code`, followed by the project's own file, which can refine it. Only
directories on the way up count: a sibling's file, or one in a subdirectory,
is not loaded this way (see the next section for subdirectories).

If you also use Claude Code, Xi is the only thing loading the project's
instructions. It turns off the Claude CLI's own project-file loading, so the
file is in the prompt once, not twice. The CLI's user-level `~/.claude/CLAUDE.md`
still loads as it always did.

## Files in subdirectories

A large repository often has more than one `AGENTS.md`: one at the root, one
in `packages/api/`, one in `site/`. Loading all of them into every chat would
cost tokens for instructions that mostly don't apply. So Xi does not load
them; it lists them.

Inside a git repository, Xi finds every file named `AGENTS.md` anywhere in the
repository and adds their paths to the system prompt, with this instruction:

```text
These AGENTS.md files have been found, read them automatically when editing files related to them:
[/AGENTS.md]
[/packages/api/AGENTS.md]
[/site/AGENTS.md]
```

Paths are relative to the repository root. The agent reads a listed file with
its `read` tool when it starts working near it, and ignores the rest. So the
rules for `packages/api/` reach the agent when it edits the API, and cost
nothing in a chat about the web client.

What the list includes:

- Every file named exactly `AGENTS.md`, tracked or not. A `CLAUDE.md` in a
  subdirectory is not listed; rename it or add an `AGENTS.md` next to it.
- Files git ignores are left out, so an `AGENTS.md` in `node_modules/` or a
  build directory never shows up.
- The root file is listed too, although its contents are already in the
  prompt.

Outside a git repository there is no list; only the files on the way up load.

## When the files are read

The prompt is built once, when the chat starts, when you resume a chat, and
when you change directory with `/cd`. It is not re-read between turns, so an
edit to `AGENTS.md` during a chat does not reach the agent until the next
chat. To pick it up in the current one, change into the same directory
again:

```text
/cd .
```

This rebuilds the prompt and the list of files from the directory you are in.
Starting a new chat with `/new` does the same.

## Seeing what loaded

- The header at the top of a new chat says `Loaded 2 AGENTS.md files`. The
  count covers the files on the way up, not the subdirectory list. The web
  client shows the same on the card for a new chat, before the first message.
- `/prompt` shows the system prompt the agent gets, file contents and the
  subdirectory list included. `/buffers` switches to the same view.

## Adding to or replacing the file

Four settings put more text next to the project's file, stand in for it, or
turn it off.
All of them are documented on their own pages; this is how they combine.

| Setting | Where | Effect |
| --- | --- | --- |
| `:agents-prompt` in a project's `:settings` | `config.edn`, see [Projects](projects.md#per-project-prompt-and-snippets-settings) | Added after the project's own files. For instructions you want in one project without committing them to it. |
| `:agents-replace true` in the same place | `config.edn` | The project root's own `AGENTS.md` (or `CLAUDE.md`) is left out and the `:agents-prompt` text takes its place. Files in parent directories still load, and the subdirectory list is still sent, without the replaced file. |
| `:agents-ignore true` in the same place | `config.edn` | None of the repository's `AGENTS.md` / `CLAUDE.md` files load, and the subdirectory list is not sent. Files in directories above the repository still load. Combine with `:agents-prompt` to use your own instructions instead. |
| `prompt-files.edn` | `~/.config/xi/prompt-files.edn`, see [Configuration](configuration.md#the-other-files) | Markdown files added to every chat on this machine, after the project's files. |

An [agent profile](agents.md) loads none of this: its `:system-prompt` is the
whole prompt, and `/cd` in a profile chat does not reload project files.
