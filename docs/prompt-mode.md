# Prompt Mode (one-shot, headless)

`xi prompt` runs a single prompt with no TUI and no server, prints the
assistant's response, and exits. It exists so prompts can be **scripted,
piped, and run safely from an agent shell** — unlike the TUI modes, it needs
no interactive terminal.

## Usage

```bash
xi prompt "summarize the architecture in one sentence"   # blocking
xi -p    "summarize the architecture in one sentence"    # short alias

xi prompt --stream "count from 1 to 10"                  # stream tokens live

echo "what does xi.wire do?" | xi prompt                 # prompt from stdin
git diff | xi -p "write a commit message for this diff"  # pipe + inline prompt
```

The prompt text is the positional argument(s). When none is given, Xi reads
the prompt from **stdin** (only if stdin is piped — a bare interactive
terminal prints a usage error instead of hanging).

### Flags

```
--stream        Stream response tokens to stdout as they arrive. Without it,
                the response is buffered and printed once the turn ends.
--model NAME    Override the default model (also honours XI_MODEL).
```

`XI_CWD` (or the current directory) sets the working directory the agent runs
in, exactly like the other modes.

## Output & exit codes

- The assistant's **text** goes to **stdout**, followed by a trailing newline.
- Errors go to **stderr**.
- Exit `0` on a completed turn, `1` on an agent error or when no prompt was
  provided.

Only assistant text is emitted — tool calls and thinking are not printed, so
the output is safe to capture and parse.

## Behaviour notes

- **No auto-titling.** A one-shot run skips the session-naming turn, so it
  makes exactly one provider call.
- **Sessions are still saved.** The turn is persisted like any other, so it
  can later be `/resume`d from a TUI or the web client.
- **Dialogs resolve to safe defaults.** Prompt mode runs unattended (no client
  attached), so permission confirms and working-directory-recovery dialogs
  resolve to their safe default instead of prompting. In practice, guarded
  operations from the permission gate (e.g. `git push`, `rm -rf`) are
  **blocked** rather than paused for confirmation.
- **`terminal-title` is disabled.** That extension writes raw ANSI escapes to
  stdout, which would corrupt the machine-readable response, so it is dropped
  from the prompt-mode assembly.

## How it works

Prompt mode reuses the standalone assembly (core + agent + commands +
compaction + server-side extensions) **minus the renderer**:

- The app is created with no `:on-render`; instead a **tap** watches the event
  stream.
- `:agent/text-delta` events are accumulated (and echoed live under
  `--stream`); `:agent/error` records the failure.
- `:agent/turn-end` flushes the buffered response (or a final newline when
  streaming), runs extension shutdown, and exits with the right code.
- The initial state uses `:server` mode with no clients, which is what makes
  `xi.ext.core/create-dialogs` auto-resolve dialogs to their safe defaults.

See `xi.cli/start-prompt!` and `run-prompt!` for the implementation, and
[architecture.md](architecture.md) for the assembly model.
