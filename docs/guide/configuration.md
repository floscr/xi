# Configuration

```clojure title="~/.config/xi/config.edn"
{:type    :xi/config
 :version 1

 :extensions ["notes.cljs" "notify.cljs"]

 :projects {:browse ["~/code"]
            :repos  ["~/.config/dotfiles"]}

 :users {"alice" {:name "Alice" :avatar "https://example.com/alice.png"
                 :meta {:team "ops"}}
         "bob"   {:name "Bob"}}

 :trusted-mcp-servers ["browser"]

 :agents {"assistant" {:system-prompt "You are a concise assistant."
                       :tools ["web_search" "fetch"]}}

 :keys {:global {"ctrl+shift+n" :chat/new}}}
```

`:type` and `:version` are required and always these values. They let Xi tell
its own file from another tool's, and let the format change without reading
an old file wrong.

| Key | What it does | Details |
| --- | --- | --- |
| `:extensions` | The extension files in `~/.config/xi/extensions/` that Xi may load. A file not listed here is never read. | [Extensions](extensions.md) |
| `:projects` | Where to look for projects, and per-project prompts and snippets. | [Projects](projects.md), keys below |
| `:users` | The people who use this server: an id, an optional display `:name` and `:avatar`, and read-only `:meta` that [extensions](extensions-reference.md#users-and-their-state) can read. | [Users](server.md#users), keys below |
| `:trusted-mcp-servers` | MCP servers whose tools run without asking: `mcp.edn` ids, or an extension's `"<extension>/<name>"`. Trusted as they are, with no code fingerprint. | [MCP servers](mcp-servers.md#trusting-a-server) |
| `:agents` | Profiles for `xi --agent <id>`: a fixed tool set and prompt. | [Agent profiles](agents.md), keys below |
| `:keys` | Keyboard shortcuts: bind, rebind or remove keys per layer, for both clients or one. | [Keyboard shortcuts](keyboard.md#changing-keys), keys below |

Xi reads the file when it needs it (listing projects, starting a chat,
loading extensions), so most edits apply without a restart. `/ext reload`
picks up a changed `:extensions` list.

### `:projects`

| Key | Default | Does |
| --- | --- | --- |
| `:browse` | `[]` | Directories to scan. An entry is a path, or `{:dir path :depth n :git? bool}`. `:depth` (1 to 6, default 1) is how many levels down to look; `:git?` (default `true`) lists only directories containing `.git` and never descends into one. Hidden directories and `node_modules` are skipped. |
| `:repos` | `[]` | Directories listed as they are, repository or not. |
| `:remember-limit` | `50` | How many repositories you worked in to list on their own; `0` turns remembering off. Kept in `~/.config/xi/state/projects.edn`, never in the config. |
| `:settings` | `{}` | Per-directory extras, keyed by the exact project path: `:agents-prompt` (a file or the text, added after the project's `AGENTS.md`), `:agents-replace` (`true` replaces the project root's own `AGENTS.md`), `:agents-ignore` (`true` skips the repo's `AGENTS.md` files and the subdirectory list), `:snippets` (`[{:label :text}]` for the web client's snippets menu). |

The agent cannot write `~/.config/xi`, and a change to this file anywhere
else, for example a dotfiles source that `config.edn` is a symlink to, asks
you every time (see [Rules](rules.md)).

The project list can also live in its own file,
`~/.config/xi/projects-config.edn`: a bare map with the keys above
(`{:browse [...] :repos [...] :settings {...}}`). Xi uses it as `:projects`
when `config.edn` has no `:projects` key, so a `config.edn` that is a symlink
into a checkout can still get a generated project list. A `:projects` in
`config.edn` wins; a file that isn't valid EDN makes the whole config invalid.

### `:users`

A map from user id to a profile. Declaring a user is optional: an id nobody
declared still works, because a user is whoever a connection says it is. The
declaration only adds a profile to it. `root` always exists and may be
declared to give it a name.

| Key | Default | Does |
| --- | --- | --- |
| `:name` | none | A display name, up to 100 characters. Public: every client sees it. |
| `:avatar` | none | An `http://` or `https://` image URL, up to 2048 characters. Public: every client sees it. Without one, the web client draws a circle with the user's initials on a colour taken from their id. Any other kind of address is rejected. |
| `:meta` | `{}` | Plain data about the user, such as a team or a role. Read-only: extensions can read it, rules can match on it (`:user {:meta {…}}`, see [Rules for some users](rules.md#rules-for-some-users)), nothing can change it but you editing this file. Plain data means strings, numbers, booleans, keywords, and vectors, sets and maps of those, under 64 KB. |

Ids are lowercase letters, digits, `.`, `_` or `-`, up to 64 characters. The
profile is read when a user connects, so an edit shows for their next
connection. What a user *does* is not configuration: their web client choices
and what extensions keep about them are stored apart, in
`~/.config/xi/state/users/<id>.edn`.

### `:agents`

| Key | Default | Does |
| --- | --- | --- |
| `:tools` | none | Tool names the model gets, or `:all`. Unlisted tools are never offered; no key means no tools. |
| `:extensions` | the top-level list | Extension files this profile loads instead; `[]` for none. |
| `:system-prompt` | a short generic prompt | The instructions; replaces `AGENTS.md`, skills and extension prompts. |
| `:system-prompt-file` | — | The same from a file; relative to `~/.config/xi/`. `:system-prompt` wins. |
| `:model` | — | The profile's model; `--model` wins. |

### `:keys`

A map of layer → `{"key" :action}`; `nil` as the action removes the key.
The layers, actions and key spelling are in [Keyboard shortcuts](keyboard.md).

| Key | Default | Does |
| --- | --- | --- |
| `<layer>` | — | Bindings for that layer (`:global`, `:mode/navigate`, `:buffer/diff`, …) on both clients. |
| `:web`, `:tui` | `{}` | The same shape, for one client; applied after the shared entries. |
| `:defaults?` | `true` | `false` drops every built-in key. Also allowed inside `:web` / `:tui`. |

Read when a client starts (the server sends it to the web client on connect).

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
| `~/.config/xi/client-key`, `clients.edn` | Your own device key and the paired devices, each with an optional user assignment. See [Server mode](server.md#pairing). |
| `~/.config/xi/tls/` | Certificate and key for [HTTPS](https.md). |
| `~/.config/xi/sessions/`, `personal-agent/` | Saved chats. See [Sessions](sessions.md). |
| `~/.config/xi/state/users/<user>.edn` | One user's web client choices (theme, appearance, collapsed groups, preferred model, recent commands and skills) and the data extensions keep about them. Written by Xi; see [The web client](web-client.md#what-follows-you) and [Users and their state](extensions-reference.md#users-and-their-state). |

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
| `XI_USER` | `root` | The user this process acts as; `--user` wins. See [Users](server.md#users). |
| `XI_THEME_MODE` | `dark` | `light` or `dark` for the terminal's code blocks. An extension can set it live; see the [extension reference](extensions-reference.md). |
| `XI_TLS_PORT` | `7443` | The HTTPS port, when a certificate is configured. |
| `XI_TLS_CERT`, `XI_TLS_KEY` | `~/.config/xi/tls/xi.crt`, `xi.key` | Certificate and key. Setting either turns HTTPS on. |
| `XI_ICON` | `desktop` | The home-screen icon variant: `desktop`, `personal` or `green`. |
| `XI_TREESITTER_DIR` | bundled | Where the tree-sitter runtime and grammars for [file outlines](reading-code.md) are. |
| `XI_GIT_LOCK_WAIT_SECS` | `600` | How long a chat waits for another chat's git operation on the same repository. |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | The Ollama endpoint. |
| `OPENCODE_API_KEY`, `OPENCODE_ZEN_API_KEY` | — | The OpenCode Zen key. See [Models](models.md). |
| `CODEX_HOME` | `~/.codex` | Where the Codex CLI keeps its login. |
| `XI_CLAUDE_CLI_PATH` | bundled | Another Claude CLI executable for the Claude provider. |

## Built-in defaults

A few display defaults are compiled into Xi rather than read from a file:
the web client's appearance defaults (viewer mode, whether tool and thinking
blocks start open) and the terminal's output truncation. Each user overrides
the appearance from the web client's Appearance dialog; those choices are
user state, not configuration. Changing the
compiled defaults means editing `src/xi/config.cljc` and rebuilding; see
[`docs/config.md`](../config.md) in the repository.
