# Command line

Every subcommand and flag of the `xi` command. `xi help` prints a short
version of this page.

## Subcommands

| Command | Does |
| --- | --- |
| `xi` | The terminal client with one local chat. If a server is already running on the port, it joins that instead (`--no-auto-join` keeps it local). |
| `xi server` | A server plus a terminal client in one process. Serves the web client at `http://localhost:<port>`. |
| `xi server --headless` | The server alone. |
| `xi start` | A headless server in the background (unless one already runs on the port), then the web client in your browser, signed in without pairing. Takes the `server` flags. The server logs to `~/.config/xi/server.log`. |
| `xi join [url]` | A terminal client joined to the most recent chat on a running server. |
| `xi create [url]` | A terminal client in a new chat on a running server. |
| `xi prompt <text>` (`xi -p`) | Send one message, print the answer, exit. Reads stdin when `<text>` is omitted. See [One-shot prompts](prompt.md). |
| `xi sessions` | List saved chats as tab-separated lines. `--all` lists every chat, `--limit N` caps it, `--json` prints JSON. |
| `xi clients [action]` | Manage paired devices: `list` (default), `pending`, `approve <code>`, `revoke <prefix-or-name>`, `user <prefix-or-name> <user-id>` (`-` clears). See [Server mode](server.md#pairing) and [Users](server.md#users). |
| `xi login <provider>` | Sign in to a provider's subscription: `claude` or `opencode` (OpenCode Go). Prints a link; for OpenCode Go a code to enter there, for Claude it asks for the code the page shows. `--alias NAME` names the account. See [Accounts](models.md#accounts). |
| `xi logout <provider> [account]` | Forget a stored account; without one, sign out where the provider can. |
| `xi account [provider] [op]` | A provider's stored accounts: list (default), `<account>` or `switch [account]` (none: the next), `login`, `alias <account> [name]`, `forget <account>`. An account is its id, email or alias. `--json` prints JSON. See [Accounts](models.md#accounts). |
| `xi help` | This, shorter. Also `--help` and `-h`. |

For `join` and `create`, `url` is `host:port` (`ws://` is added) or a full
`ws://` or `wss://` URL. Without it, `ws://localhost:<port>`.

## Flags

| Flag | Applies to | Does |
| --- | --- | --- |
| `--port N` | all | The port (default `1414`, or `XI_PORT`). |
| `--host ADDR` | `server` | Addresses to bind, comma-separated (default all interfaces, or `XI_HOST`). Loopback is always bound too. |
| `--model NAME` | all | The model. Without it: the last one your [user](server.md#users) picked with `/model`, else the built-in default. |
| `--session ID` | `xi`, `join`, `create`, `prompt` | Resume a saved chat instead of starting a new one. |
| `--prompt TEXT` | `xi`, `join`, `create` | Send this message as soon as the chat is ready. |
| `--no-auto-join` | `xi` | Stay local even when a server is running. |
| `--join` / `--create` | `xi` | Put a plain `xi` onto the running server, in the latest or a new chat. |
| `--headless` | `server` | No terminal client. |
| `--no-pairing` | `server` | Admit every client without a [pairing code](server.md). Anyone who reaches the port gets in; bind it to trusted addresses only. |
| `--agent ID` | `xi`, `server`, `prompt` | Run an [agent profile](agents.md). A plain `xi --agent ID` stays local. |
| `--user ID` | all | The user this process acts as (default `root`, or `XI_USER`). A joining terminal claims it; a server owns its own prompts under it. See [Users](server.md#users). |
| `--stream` | `prompt` | Print tokens as they arrive. |
| `--no-store` | `prompt` | Leave no session behind. |
| `--json` | `prompt`, `sessions`, `account` | Machine-readable output. |
| `--alias NAME` / `--email ADDR` | `login`, `account … login` | The new account's alias / the address to sign in as. |
| `--all` / `--limit N` | `sessions` | Every chat / at most N. |
| `--debug-events` | `xi`, `server` | Write the full event stream to a JSONL file, for debugging. |
| `--no-hardened-rules` | all | Drop the non-overridable rules tier. Unsafe; only the person launching Xi can set it. |

## Environment

`XI_PORT`, `XI_HOST`, `XI_CWD`, `XI_USER`, `ANTHROPIC_API_KEY` and the rest are listed
in [Configuration](configuration.md#environment-variables).

## Examples

```sh
xi                                         # terminal client
xi start                                   # background server + browser
xi server --headless --port 1415           # headless server on another port
xi join                                    # attach to the latest chat on :1414
xi create ws://host:1414                   # new chat on a remote server
xi --session 0192abcd-…                    # resume a saved chat

xi prompt "summarise the architecture in one sentence"
git diff | xi -p --no-store "write a commit message"
xi prompt --agent coach --json "hi"

xi account claude                          # stored Claude accounts
xi account claude work                     # switch Claude to "work"
xi login claude --alias spare              # sign in to another Claude account

xi sessions --json | jq '.[0]'
xi clients approve 1234
xi join --user alice                       # act as alice on a shared server
xi clients user iPhone alice               # assign a paired phone to alice
```
