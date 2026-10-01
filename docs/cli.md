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
| `xi sessions` | List saved chats (the web sidebar's Recent set) as TSV, then exit. Machine-facing; no TUI, no server. `--all` lists every chat, `--limit N` caps the count, `--json` emits a JSON array. |
| `xi clients [action]` | Manage the client-key auth store (`~/.config/xi/clients.edn`) from the shell: `list` (default), `pending`, `approve <code>`, `revoke <key-prefix\|name>`. The CLI counterpart to the web pairing banner and the repo's `bb serve:*` tasks — use it to approve pairing codes over ssh on a headless server (no repo checkout needed; a running server admits approvals within ~2s). See [client-auth.md](client-auth.md). |
| `xi help` | Print the built-in help and exit (also `--help`, `-h`). |

For `join` / `create`, the positional `url` may be a bare `host:port` (it's
prefixed with `ws://` automatically) or a full `ws://…` / `wss://…` URL.
Defaults to `ws://localhost:<port>`.

## Flags

| Flag | Applies to | Description |
| --- | --- | --- |
| `--port N` | all | Override the default port (`7474`; falls back to `XI_PORT`). |
| `--model NAME` | all | Override the default model. Without it: the last model picked with `/model`, else the built-in default. |
| `--session ID` | standalone, `join`, `create`, `prompt` | Resume a saved session by its id instead of opening a fresh room. In prompt mode this continues the saved conversation (the provider transcript is resumed) — chain one-shots into a stateful conversation. |
| `--prompt TEXT` | standalone, client | Send an initial prompt as soon as the room is ready. |
| `--no-auto-join` | standalone | Stay a local room; don't connect to a running server. |
| `--join` / `--create` | standalone | Redirect the bare `xi` invocation onto a running server (latest / new room). |
| `--headless` | `server` | Run the server without a local TUI; clients attach remotely. |
| `--agent ID` | standalone, `server`, `prompt` | Run as a **named agent**: the profile `[:agents ID]` in `~/.config/xi/config.edn` decides which tools the model gets, which user extensions load, and its system prompt (no AGENTS.md/skills context); sessions are stored per agent in `~/.config/xi/personal-agent/<ID>/`. A bare `xi --agent ID` stays a local TUI room (no auto-join) — see [Agent profiles](#agent-profiles) below. |
| `--debug-events` | standalone, `server` | Write the full event stream as JSONL (see [architecture.md](architecture.md)). |
| `--no-hardened-rules` | all | Drop the non-overridable hardened rules tier (see [rules.md](rules.md)). Unsafe; a launch-time operator override agents cannot set. |
| `--stream` | `prompt` | Stream response tokens to stdout as they arrive (otherwise buffered until the turn ends). |
| `--no-store` | `prompt` | Run ephemerally: the turn uses a throwaway `CLAUDE_CONFIG_DIR` that is deleted on exit and the Xi session save is skipped, so it leaves no session anywhere and never appears in any session list. See [prompt-mode.md](prompt-mode.md). |
| `--json` | `prompt`, `sessions` | prompt: emit `{"session-id": …, "text": …}` instead of raw text — pass the id back via `--session` to continue the conversation programmatically. sessions: emit a JSON array instead of TSV. |
| `--all` / `--limit N` | `sessions` | List every saved chat, not just recent / cap the number listed. |

## Agent profiles

`xi --agent <id>` (local TUI), `xi server --agent <id>` and
`xi prompt --agent <id>` run xi as a named agent instead of a coding agent.
Everything that makes the agent what it is lives in the profile under
`[:agents <id>]` in `~/.config/xi/config.edn`
(see [config.md](config.md#agent-profiles-configxiconfigedn)):

```clojure
;; ~/.config/xi/config.edn
{:type    :xi/config
 :version 1
 :agents  {"root"  {:system-prompt-file "agents/root.md"
                    :model "claude-sonnet-4-6"
                    :extensions ["freesearch.cljs" "web.cljs"]
                    :tools ["web_search" "fetch"]}
           "coach" {:system-prompt-file "personal-agent/coach/prompt.md"
                    :extensions ["freesearch.cljs"]
                    :tools ["web_search"]}}}
```

The file is typed and version-locked like `rules.edn`; an invalid file fails
closed (every profile loads with no tools) — see
[config.md](config.md#agent-profiles-configxiconfigedn).

| Key | Meaning |
| --- | --- |
| `:tools` | The tool names the model gets — builtins and extension tools alike — or `:all`. Unlisted tools are never advertised, so the model cannot call them. **Absent, or no profile at all, means no tools**: a typo can't turn a restricted agent into a coding agent. |
| `:extensions` | The [user extension](user-extensions.md) files (`~/.config/xi/extensions/`) this agent loads, replacing the global `:extensions` list of `rules.edn` for the process. Absent = the global list; `[]` = none. Keeps a coding machine's extensions (knowledge base, notifiers, …) out of an assistant. Built-in extensions still load; their tools are hidden by `:tools` and their prompts by the profile prompt. |
| `:system-prompt` | System prompt text; replaces every project prompt part (AGENTS.md, profile, skills, extension prompts). |
| `:system-prompt-file` | Path to a file holding the prompt (`~` expanded; relative paths resolve against `~/.config/xi/`). `:system-prompt` wins over it; with neither, a short generic assistant prompt is used. |
| `:model` | Default model for this agent. Precedence: `--model` flag > profile > last `/model` pick > built-in default. |

An agent's sessions live in their own directory,
`~/.config/xi/personal-agent/<id>/` — one per consumer application (a fitness
coach, a finance categorizer, …), fully isolated from coding sessions and from
each other. A server started with `--agent` lists only those sessions and
shows no projects; a local TUI (`xi --agent <id>`) and one-shots run in that
directory. `root` is the conventional id for the general-purpose assistant;
any string works, but keep ids shell- and path-friendly (`health-coach`,
not `"Personal coach"`) since the id is also the directory name.

One machine can hold any number of flavours this way — a research assistant
with search tools, a coach with none, a reviewer with `:tools :all` and its
own prompt — each a few lines of config.

Whether a listed tool may *run* is still the rules engine's call: agent rooms
carry `[:ext :agent {:id "<id>"}]`, so a rule can target one agent with
`:when {:agent {:id "root"}}` (see [rules.md](rules.md)). The config file
sits under `~/.config/xi`, which agents can never write, so the allowlist is
the operator's alone.

The intended scripting loop:

```bash
# first turn — returns the session id
xi prompt --agent coach --json "I ran 5k today"
# → {"session-id":"0198…","text":"Nice pace! …"}

# later turns — same conversation, no history re-sending needed
xi prompt --agent coach --session 0198… --json "how does that compare to last week?"
```

From Babashka/JVM services, use the bundled client lib instead of spawning
the process by hand — see [bb-client.md](bb-client.md).

## Environment

The CLI honours `XI_PORT`, `XI_CWD` and `ANTHROPIC_API_KEY` — see
[config.md](config.md#environment-variables) for defaults and the full list.

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
