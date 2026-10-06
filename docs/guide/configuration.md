# Configuration

## The file

```clojure title="~/.config/xi/config.edn"
{:type    :xi/config
 :version 1

 :extensions ["notes.cljs" "notify.cljs"]

 :projects {:browse ["~/code"]
            :repos  ["~/.config/dotfiles"]}

 :trusted-mcp-servers ["browser"]

 :agents {"assistant" {:system-prompt "You are a concise assistant."
                       :tools ["web_search" "fetch"]}}}
```

`:type` and `:version` are required and always these values. They let Xi tell
its own file from another tool's, and let the format change without reading
an old file wrong.

| Key | What it does | Details |
| --- | --- | --- |
| `:extensions` | The extension files in `~/.config/xi/extensions/` that Xi may load. A file not listed here is never read. | [Extensions](extensions.md) |
| `:projects` | Where to look for projects, and per-project prompts and snippets. | [Projects](projects.md), keys below |
| `:trusted-mcp-servers` | MCP servers whose tools run without asking: `mcp.edn` ids, or an extension's `"<extension>/<name>"`. Trusted as they are, with no code fingerprint. | [MCP servers](mcp-servers.md#trusting-a-server) |
| `:agents` | Profiles for `xi --agent <id>`: a fixed tool set and prompt. | [Agent profiles](agents.md), keys below |

Xi reads the file when it needs it (listing projects, starting a chat,
loading extensions), so most edits apply without a restart. `/ext reload`
picks up a changed `:extensions` list.

### `:projects`

| Key | Default | Does |
| --- | --- | --- |
| `:browse` | `[]` | Directories to scan. An entry is a path, or `{:dir path :depth n :git? bool}`. `:depth` (1 to 6, default 1) is how many levels down to look; `:git?` (default `true`) lists only directories containing `.git` and never descends into one. Hidden directories and `node_modules` are skipped. |
| `:repos` | `[]` | Directories listed as they are, repository or not. |
| `:remember-limit` | `50` | How many repositories you worked in to list on their own; `0` turns remembering off. Kept in `~/.config/xi/state/projects.edn`, never in the config. |
| `:settings` | `{}` | Per-directory extras, keyed by the exact project path: `:agents-prompt` (a file or the text, added after the project's `AGENTS.md`), `:agents-replace` (`true` replaces the project root's own `AGENTS.md`), `:snippets` (`[{:label :text}]` for the web client's snippets menu). |

### `:agents`

| Key | Default | Does |
| --- | --- | --- |
| `:tools` | none | Tool names the model gets, or `:all`. Unlisted tools are never offered; no key means no tools. |
| `:extensions` | the top-level list | Extension files this profile loads instead; `[]` for none. |
| `:system-prompt` | a short generic prompt | The instructions; replaces `AGENTS.md`, skills and extension prompts. |
| `:system-prompt-file` | — | The same from a file; relative to `~/.config/xi/`. `:system-prompt` wins. |
| `:model` | — | The profile's model; `--model` wins. |

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
| `~/.config/xi/mcp.edn` | The MCP servers you added. Written by `/mcp add`; you can edit it. See [MCP servers](mcp-servers.md#the-registry-file). |
| `~/.config/xi/mcp/<id>/tools.edn` | A server's cached tool list, so its tools are offered without starting it. `/mcp refresh` rewrites it. |
| `~/.config/xi/snippets.edn` | Prompt snippets for the web client's snippets menu, everywhere. |
| `~/.config/xi/prompt-files.edn` | A vector of markdown paths added to every chat's system prompt on this machine, after `AGENTS.md`. For personal instructions that belong to the machine, not a project. |
| `~/.config/xi/ext/<name>.env` | Secrets an MCP server needs, as `KEY=value` lines. A variable of the same name in the environment wins. |
| `~/.config/xi/ext/clj.edn` | `{:allow-clis [...] :helper-hints true}` for the [clj tool](clj-tool-reference.md). |
| `~/.config/xi/ext/bb-trust.edn` | Trusted `bb.edn` hashes. Written by Xi. |
| `~/.config/xi/ext/mcp-trust.edn` | Trusted MCP server fingerprints. Written by Xi. |
| `~/.config/xi/client-key`, `clients.edn` | Your own device key and the paired devices. See [Server mode](server.md#pairing). |
| `~/.config/xi/tls/` | Certificate and key for [HTTPS](https.md). |
| `~/.config/xi/sessions/`, `personal-agent/` | Saved chats. See [Sessions](sessions.md). |

Everything under `~/.config/xi` is off-limits to the agent: no tool can read
the keys in it or write to it. The exception is the sessions directory, which
the agent may read to search earlier chats.

## Environment variables

Settings that belong to the machine rather than to you:

| Variable | Default | Does |
| --- | --- | --- |
| `ANTHROPIC_API_KEY` | — | Use an API key instead of the Claude login. |
| `XI_PORT` | `7474` | The port the server listens on and clients connect to. |
| `XI_HOST` | all interfaces | Addresses the server binds, comma-separated. Loopback is always included. |
| `XI_CWD` | the current directory | The directory a chat starts in. |
| `XI_THEME_MODE` | `dark` | `light` or `dark` for the terminal's code blocks. An extension can set it live; see the [extension reference](extensions-reference.md). |
| `XI_TLS_PORT` | `7443` | The HTTPS port, when a certificate is configured. |
| `XI_TLS_CERT`, `XI_TLS_KEY` | `~/.config/xi/tls/xi.crt`, `xi.key` | Certificate and key. Setting either turns HTTPS on. |
| `XI_ICON` | `desktop` | The home-screen icon variant: `desktop`, `personal` or `green`. |
| `XI_TREESITTER_DIR` | bundled | Where the tree-sitter runtime and grammars for file outlines are. |
| `XI_GIT_LOCK_WAIT_SECS` | `600` | How long a chat waits for another chat's git operation on the same repository. |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | The Ollama endpoint. |
| `OPENCODE_API_KEY`, `OPENCODE_ZEN_API_KEY` | — | The OpenCode Zen key. See [Models](models.md). |
| `CODEX_HOME` | `~/.codex` | Where the Codex CLI keeps its login. |
| `XI_CLAUDE_CLI_PATH` | bundled | Another Claude CLI executable for the Claude provider. |

## Built-in defaults

A few display defaults are compiled into Xi rather than read from a file:
the web client's appearance defaults (viewer mode, whether tool and thinking
blocks start open) and the terminal's output truncation. The web client
overrides the appearance per browser from its Appearance dialog. Changing the
compiled defaults means editing `src/xi/config.cljc` and rebuilding; see
[`docs/config.md`](../config.md) in the repository.
