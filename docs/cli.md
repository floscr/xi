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
| `xi clients [action]` | Manage the client-key auth store (`~/.config/xi/clients.edn`) from the shell: `list` (default), `pending`, `approve <code>`, `revoke <key-prefix\|name>`. The CLI counterpart to the web pairing banner and the repo's `bb serve:*` tasks — use it to approve pairing codes over ssh on a headless server (no repo checkout needed; a running server admits approvals within ~2s). See [client-auth.md](client-auth.md). |
| `xi help` | Print the built-in help and exit (also `--help`, `-h`). |

For `join` / `create`, the positional `url` may be a bare `host:port` (it's
prefixed with `ws://` automatically) or a full `ws://…` / `wss://…` URL.
Defaults to `ws://localhost:<port>`.

## Flags

| Flag | Applies to | Description |
| --- | --- | --- |
| `--port N` | all | Override the default port (`7474`; falls back to `XI_PORT`). |
| `--model NAME` | all | Override the default model (also honours `XI_MODEL`). |
| `--session ID` | standalone, `join`, `create`, `prompt` | Resume a saved session by its id instead of opening a fresh room. In prompt mode this continues the saved conversation (the provider transcript is resumed) — chain one-shots into a stateful conversation. |
| `--prompt TEXT` | standalone, client | Send an initial prompt as soon as the room is ready. |
| `--no-auto-join` | standalone | Stay a local room; don't connect to a running server. |
| `--join` / `--create` | standalone | Redirect the bare `xi` invocation onto a running server (latest / new room). |
| `--headless` | `server` | Run the server without a local TUI; clients attach remotely. |
| `--personal-agent-only` | `server`, `prompt` | Personal-assistant mode — no coding tools, `web_search` only. In prompt mode the run also gets no AGENTS.md/skills context, only the personal-agent system prompt. |
| `--agent ID` | `prompt` | Run as a **named personal agent** (implies `--personal-agent-only`). Sessions are stored per agent in `~/.config/xi/personal-agent/<ID>/`, and an optional `agent.edn` there customizes the agent — see [Named agents](#named-agents) below. |
| `--debug-events` | standalone, `server` | Write the full event stream as JSONL (see [architecture.md](architecture.md)). |
| `--stream` | `prompt` | Stream response tokens to stdout as they arrive (otherwise buffered until the turn ends). |
| `--no-store` | `prompt` | Run ephemerally: the turn uses a throwaway `CLAUDE_CONFIG_DIR` that is deleted on exit and the Xi session save is skipped, so it leaves no session anywhere and never appears in any session list. See [prompt-mode.md](prompt-mode.md). |
| `--json` | `prompt`, `sessions` | prompt: emit `{"session-id": …, "text": …}` instead of raw text — pass the id back via `--session` to continue the conversation programmatically. sessions: emit a JSON array instead of TSV. |

## Named agents

`xi prompt --agent <id>` runs the one-shot as a named personal agent: a
personal-assistant-mode run (no coding tools) whose sessions live in their own
directory, `~/.config/xi/personal-agent/<id>/` — one directory per consumer
application (a fitness coach, a finance categorizer, …), fully isolated from
coding sessions and from each other.

An optional `agent.edn` in that directory customizes the agent:

```clojure
;; ~/.config/xi/personal-agent/coach/agent.edn
{:system-prompt-file "prompt.md"          ; or :system-prompt "inline text…"
 :model              "claude-haiku-4-5-20251001"}
```

| Key | Meaning |
| --- | --- |
| `:system-prompt` | System prompt text; replaces the default personal-agent prompt. |
| `:system-prompt-file` | Path to a file holding the system prompt (relative paths resolve against the agent dir). Wins over the default; `:system-prompt` wins over it. |
| `:model` | Default model for this agent. Precedence: `--model` flag > `agent.edn` > `XI_MODEL` > built-in default. |

The intended scripting loop:

```bash
# first turn — returns the session id
xi prompt --agent coach --json "I ran 5k today"
# → {"session-id":"0198…","text":"Nice pace! …"}

# later turns — same conversation, no history re-sending needed
xi prompt --agent coach --session 0198… --json "how does that compare to last week?"
```

The root agent (plain `--personal-agent-only`, sessions in
`…/personal-agent/root/`) reads an `agent.edn` the same way.

From Babashka/JVM services, use the bundled client lib instead of spawning
the process by hand — see [bb-client.md](bb-client.md).

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

xi prompt --agent coach --json "hi"        # named agent, JSON out
xi prompt --agent coach --session 0198… "…" # continue that conversation

xi join                                    # attach to the latest room on :7474
xi create ws://host:7474                   # new room on a remote server
xi --session 0192abcd-…                    # resume a saved session
```

See also: [server.md](server.md) (server/client modes in depth),
[prompt-mode.md](prompt-mode.md) (one-shot mode), and
[commands.md](commands.md) (in-app slash commands).
