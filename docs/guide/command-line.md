# Command line

Every subcommand and flag of the `xi` command. `xi help` prints a short
version of this page.

## Subcommands

| Command | Does |
| --- | --- |
| `xi` | The terminal client with one local chat. If a server is already running on the port, it joins that instead (`--no-auto-join` keeps it local). |
| `xi server` | A server plus a terminal client in one process. Serves the web client at `http://localhost:<port>`. |
| `xi server --headless` | The server alone. |
| `xi join [url]` | A terminal client joined to the most recent chat on a running server. |
| `xi create [url]` | A terminal client in a new chat on a running server. |
| `xi prompt <text>` (`xi -p`) | Send one message, print the answer, exit. Reads stdin when `<text>` is omitted. See [One-shot prompts](prompt.md). |
| `xi sessions` | List saved chats as tab-separated lines. `--all` lists every chat, `--limit N` caps it, `--json` prints JSON. |
| `xi clients [action]` | Manage paired devices: `list` (default), `pending`, `approve <code>`, `revoke <prefix-or-name>`. See [Server mode](server.md#pairing). |
| `xi help` | This, shorter. Also `--help` and `-h`. |

For `join` and `create`, `url` is `host:port` (`ws://` is added) or a full
`ws://` or `wss://` URL. Without it, `ws://localhost:<port>`.

## Flags

| Flag | Applies to | Does |
| --- | --- | --- |
| `--port N` | all | The port (default `7474`, or `XI_PORT`). |
| `--host ADDR` | `server` | Addresses to bind, comma-separated (default all interfaces, or `XI_HOST`). Loopback is always bound too. |
| `--model NAME` | all | The model. Without it: the last one picked with `/model`, else the built-in default. |
| `--session ID` | `xi`, `join`, `create`, `prompt` | Resume a saved chat instead of starting a new one. |
| `--prompt TEXT` | `xi`, `join`, `create` | Send this message as soon as the chat is ready. |
| `--no-auto-join` | `xi` | Stay local even when a server is running. |
| `--join` / `--create` | `xi` | Put a plain `xi` onto the running server, in the latest or a new chat. |
| `--headless` | `server` | No terminal client. |
| `--agent ID` | `xi`, `server`, `prompt` | Run an [agent profile](agents.md). A plain `xi --agent ID` stays local. |
| `--stream` | `prompt` | Print tokens as they arrive. |
| `--no-store` | `prompt` | Leave no session behind. |
| `--json` | `prompt`, `sessions` | Machine-readable output. |
| `--all` / `--limit N` | `sessions` | Every chat / at most N. |
| `--debug-events` | `xi`, `server` | Write the full event stream to a JSONL file, for debugging. |
| `--no-hardened-rules` | all | Drop the non-overridable rules tier. Unsafe; only the person launching Xi can set it. |

## Environment

`XI_PORT`, `XI_HOST`, `XI_CWD`, `ANTHROPIC_API_KEY` and the rest are listed
in [Configuration](configuration.md#environment-variables).

## Examples

```sh
xi                                         # terminal client
xi server --headless --port 7475           # headless server on another port
xi join                                    # attach to the latest chat on :7474
xi create ws://host:7474                   # new chat on a remote server
xi --session 0192abcd-…                    # resume a saved chat

xi prompt "summarise the architecture in one sentence"
git diff | xi -p --no-store "write a commit message"
xi prompt --agent coach --json "hi"

xi sessions --json | jq '.[0]'
xi clients approve 1234
```
