# One-shot prompts

`xi prompt` sends one message, prints the answer, and exits. No terminal
interface, no server. Use it from scripts, pipes and other programs.

## Usage

```sh
xi prompt "summarise this repository in three sentences"
xi -p "same, shorter"                           # short form

git diff | xi prompt "write a commit message for this diff"
echo "what does src/app/db.cljs do?" | xi prompt   # prompt from stdin

xi prompt --stream "count to ten"              # print tokens as they arrive
```

The answer goes to stdout and ends with a newline. Errors go to stderr. The
exit code is 0 on success and 1 on an error or when no prompt was given.
Only the final text is printed, never tool calls or thinking, so the output
is safe to capture.

## Flags

| Flag | Does |
| --- | --- |
| `--stream` | Print tokens as they arrive instead of all at once |
| `--json` | Print `{"session-id": …, "text": …}` |
| `--session <id>` | Continue that conversation; the agent remembers earlier turns |
| `--no-store` | Leave no session behind |
| `--model <name>` | Use another model |
| `--agent <id>` | Run an [agent profile](agents.md) |

## Conversations from a script

The session id from `--json` continues the same conversation later, without
sending the history again:

```sh
first=$(xi prompt --json "What is in this project?")
id=$(echo "$first" | jq -r '."session-id"')
xi prompt --session "$id" "And how is it tested?"
```

Run from the same directory each time; the session is tied to it.

## What is different

- **Permission requests are denied.** Nobody is there to answer, so anything
  that would ask is blocked. Writes inside the project and read-only commands
  run as usual; a `git push` or a program that has never been allowed does
  not. Allow them with a [rule](rules.md) if a script needs them.
- **The chat is saved** like any other, unless `--no-store`. You can open it
  later with `/resume` or in the browser.
- **Untrusted input.** For text from other people or other programs, combine
  `--agent` with a profile that has only the tools the task needs, and
  `--no-store`.

## Reference

[Prompt mode](../prompt-mode.md) and, for Babashka programs, the
[client library](../bb-client.md).
