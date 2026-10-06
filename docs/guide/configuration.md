# Configuration

Xi reads one optional file, `~/.config/xi/config.edn`. It names your
extensions, your projects, your agent profiles and the MCP servers you trust.
Everything in it is optional; Xi runs fine without the file.

## The file

```clojure
;; ~/.config/xi/config.edn
{:type    :xi/config
 :version 1

 :extensions ["notes.cljs" "notify.cljs"]

 :projects {:browse ["~/code"]
            :repos  ["~/.config/dotfiles"]}

 :trusted-mcp-servers ["context7"]

 :agents {"assistant" {:system-prompt "You are a concise assistant."
                       :tools ["web_search" "fetch"]}}}
```

`:type` and `:version` are required and always these values. They let Xi tell
its own file from another tool's, and let the format change without reading
an old file wrong.

| Key | What it does | Details |
| --- | --- | --- |
| `:extensions` | The extension files in `~/.config/xi/extensions/` that Xi may load. A file not listed here is never read. | [Extensions](extensions.md) |
| `:projects` | Where to look for projects, and per-project prompts and snippets. | [Projects](projects.md) |
| `:trusted-mcp-servers` | MCP servers whose tools run without asking. | [MCP servers](mcp-servers.md#trusting-a-server) |
| `:agents` | Profiles for `xi --agent <id>`: a fixed tool set and prompt. | [Agent profiles](agents.md) |

Xi reads the file when it needs it (listing projects, starting a chat,
loading extensions), so most edits apply without a restart. `/ext reload`
picks up a changed `:extensions` list.

## When the file is invalid

Xi rejects the **whole file** when any part of it is wrong: a missing `:type`
or `:version`, a key it does not know, a value of the wrong shape, or EDN it
cannot read. The error on startup names the problem:

```text
[config] ~/.config/xi/config.edn: :projects :browse entries must be a dir string or {:dir d :depth 1..6 :git? bool}
```

Until it is fixed, no extensions load, no projects are listed and every agent
profile runs with no tools. Nothing half-applies, so a typo cannot quietly
drop one setting while keeping the rest.

## The other files

| File | What it is |
| --- | --- |
| `~/.config/xi/rules.edn` | Your permission rules. See [Permissions and rules](rules.md). |
| `<project>/.xi/rules.edn` | Rules for one repository, checked in with it. |
| `~/.config/xi/mcp.edn` | The MCP servers you added. Written by `/mcp add`; you can edit it. |
| `~/.config/xi/snippets.edn` | Prompt snippets for the web client's snippets menu, everywhere. |
| `~/.config/xi/prompt-files.edn` | Markdown files to add to every chat's system prompt on this machine. |
| `~/.config/xi/ext/<name>.env` | Secrets an MCP server needs, as `KEY=value` lines. Never in `mcp.edn`. |
| `~/.config/xi/ext/clj.edn` | Commands the [clj tool](clj-tool.md) may run without asking, on every project. |

Everything under `~/.config/xi` is off-limits to the agent: no tool can read
the keys in it or write to it. The exception is the sessions directory, which
the agent may read to search earlier chats.

## Environment variables

A few settings are environment variables, because they belong to the machine
rather than to you:

| Variable | Default | Does |
| --- | --- | --- |
| `ANTHROPIC_API_KEY` | — | Use an API key instead of the Claude login. |
| `XI_PORT` | `7474` | The port the server listens on and clients connect to. |
| `XI_HOST` | all interfaces | Addresses the server binds. Loopback is always included. |
| `XI_CWD` | the current directory | The directory a chat starts in. |
| `XI_THEME_MODE` | `dark` | `light` or `dark` for the terminal's code blocks. |

## Reference

Every key and variable, including the ones for TLS and other model providers,
is in [the configuration reference](../config.md).
