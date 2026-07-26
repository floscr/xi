# CLI reference

`xi` is a single binary with a few subcommands. Run `xi help` (also `--help`,
`-h`) to print a condensed version of this page.

```bash
xi help
xi --help
xi -h
```

## Commands

| Command | What it does |
| --- | --- |
| `xi` | Standalone TUI. One local room, connected to nothing. If a server is already listening on the port it transparently **auto-joins** it (disable with `--no-auto-join`). |
| `xi server` | Start a WS server **and** a local TUI client in the same process. Also serves the web client at `http://localhost:<port>`. |
| `xi prompt <text>` (alias `xi -p`) | One-shot headless run: send a single prompt, print the assistant's response, and exit. No TUI, no server — safe to script, pipe, and run from an agent shell. Reads **stdin** when `<text>` is omitted. See [prompt-mode.md](prompt-mode.md). |
| `xi join [url]` | Connect a TUI client to the **latest** room on a running server. |
| `xi create [url]` | Connect a TUI client to a **new** room on a running server. |
| `xi help` | Print the built-in help and exit (also `--help`, `-h`). |

For `join` / `create`, the positional `url` may be a bare `host:port` (it's
prefixed with `ws://` automatically) or a full `ws://…` / `wss://…` URL.
Defaults to `ws://localhost:<port>`.

## Flags

| Flag | Applies to | Description |
| --- | --- | --- |
| `--port N` | all | Override the default port (`7474`; falls back to `XI_PORT`). |
| `--model NAME` | all | Override the default model (also honours `XI_MODEL`). |
| `--session ID` | standalone, `join`, `create` | Resume a saved session by its id instead of opening a fresh room. |
| `--prompt TEXT` | standalone, client | Send an initial prompt as soon as the room is ready. |
| `--no-auto-join` | standalone | Stay a local room; don't connect to a running server. |
| `--join` / `--create` | standalone | Redirect the bare `xi` invocation onto a running server (latest / new room). |
| `--headless` | `server` | Run the server without a local TUI; clients attach remotely. |
| `--personal-agent-only` | `server`, `prompt` | Personal-assistant mode — no coding tools, `web_search` only. In prompt mode the run also gets no AGENTS.md/skills context, only the personal-agent system prompt. |
| `--debug-events` | standalone, `server` | Write the full event stream as JSONL (see [architecture.md](architecture.md)). |
| `--stream` | `prompt` | Stream response tokens to stdout as they arrive (otherwise buffered until the turn ends). |
| `--no-store` | `prompt` | Run ephemerally: the turn uses a throwaway `CLAUDE_CONFIG_DIR` that is deleted on exit, so it leaves no session in `~/.claude/projects` and never appears in any session list. See [prompt-mode.md](prompt-mode.md). |

## Environment

| Variable | Default | Purpose |
| --- | --- | --- |
| `XI_MODEL` | `claude-opus-4-8` | Default model when `--model` is omitted. |
| `XI_EFFORT` | `high` | Reasoning effort. |
| `XI_PORT` | `7474` | Default port when `--port` is omitted. |
| `XI_CWD` | current dir | Working directory the agent runs in. |
| `ANTHROPIC_API_KEY` | — | Auth. Alternatively, OAuth tokens in `~/.pi/agent/auth.json`. |
| `CLAUDE_CONFIG_DIR` | `~/.claude` | Claude CLI config directory. |

See [config.md](config.md) for the full configuration reference (config file,
TUI options, TLS ports, and more).

## Examples

```bash
xi                                         # standalone TUI
xi server                                  # server + local TUI
xi server --headless                       # headless server (clients attach remotely)
xi server --headless --port 7475           # on a custom port

xi prompt "summarize the architecture in one sentence"   # one-shot, buffered
xi -p    "count from 1 to 10" --stream                   # stream tokens live
echo "what does xi.wire do?" | xi prompt                 # prompt from stdin
git diff | xi -p --no-store "write a commit message"     # pipe + ephemeral run

xi join                                    # attach to the latest room on :7474
xi create ws://host:7474                   # new room on a remote server
xi --session 0192abcd-…                    # resume a saved session
```

See also: [server.md](server.md) (server/client modes in depth),
[prompt-mode.md](prompt-mode.md) (one-shot mode), and
[commands.md](commands.md) (in-app slash commands).
