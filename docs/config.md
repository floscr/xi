# Configuration

Xi has two configuration surfaces:

1. **`src/xi/config.cljc`** — compile-time config: which extensions load on each
   surface, and the `tui` display-override map.
2. **Environment variables** — runtime knobs for models, ports, auth, and
   individual extensions.

There is no separate config file on disk; `config.cljc` is compiled into the
build. Editing it requires a recompile (the shadow-cljs watch picks it up
automatically — see [AGENTS.md](../AGENTS.md)).

---

## TUI display options (`xi.config/tui`)

User-tunable TUI options live in the `tui` map in `src/xi/config.cljc`. Only the
keys you want to change from their default belong there — every option has a
default baked into its consuming namespace, so an empty map is fully functional:

```clojure
(def tui
  {:truncate-output-block-after-n-lines 200})
```

The effective value is read via `xi.config/tui-opt`, which falls back to the
default when the key is absent. Options are declared at their consuming call
site with the `deftui-opt` macro (`xi.config-macros`), which pairs the default
with the config lookup in one form.

| Key | Default | Owner namespace | Description |
| --- | --- | --- | --- |
| `:truncate-output-block-after-n-lines` | `100` | `xi.client.view` | Max tool-output lines rendered in a tool block before the remainder collapses into a `... (N more lines)` marker. |

### Adding a new TUI option

1. Declare it at the call site that reads it with `deftui-opt`:

   ```clojure
   (deftui-opt my-option 42
     "What this option does. Overridable via :my-option in xi.config/tui.")
   ```

   This expands to a private `def` whose value is
   `(xi.config/tui-opt :my-option 42)`.

2. Document it in **two** places: the `deftui-opt` docstring (above) and the
   `tui` map's docstring in `config.cljc`.

3. Add a row to the table in this file.

The macro lives in its own `.clj` (`xi.config-macros`), not in `config.cljc`,
because a `.cljc` whose requires all sit behind `:node`/`:browser` reader
features can't be loaded as a JVM macro namespace.

---

## Extension loading (`xi.config/server` · `client` · `web`)

`config.cljc` also declares which extensions load per surface. Each is a vector
of extension maps or factory fns `(fn [ctx] → ext|nil)`, composed in order by
`ext/compose` at assembly time. **Order matters** — handlers and tool-gates
chain in list order.

- `server` (`:node`) — extensions whose state + provider/tool hooks run
  server-side (server, standalone, mirrored into clients).
- `client` (`:node`) — process-local extensions in the TUI client process.
- `web` (`:browser`) — browser-safe extension web halves.

To disable an extension, remove it from the relevant vector; to add one, require
its namespace and add its `extension`/factory to the vector. See
[extensions.md](extensions.md) and [writing-extensions.md](writing-extensions.md).

---

## Feature flags (`xi.config`)

Plain `def`s in `src/xi/config.cljc`, read directly by their consuming
namespace.

| Flag | Default | What it does | Owner |
| --- | --- | --- | --- |
| `quick-replies?` | `false` (disabled) | After each finished assistant turn, run a cheap model (Haiku) over the final message to detect a decision point (yes/no, pick-one) and show one-tap **quick-reply chips** below the response. The response text is never modified — chips are additive UI, and tapping one sends a predefined message. A cheap regex gate runs first, so most turns never call the model. Set to `true` to enable. | `xi.quick-replies` |

---

## Environment variables

### Core / model

| Variable | Default | Description |
| --- | --- | --- |
| `XI_MODEL` | `claude-opus-4-8` | Default model id. |
| `XI_EFFORT` | `high` | Reasoning effort. |
| `XI_CWD` | `process.cwd()` | Working directory for the room. |
| `ANTHROPIC_API_KEY` | — | API auth, passed through to the Claude CLI (otherwise the CLI's own login is used). |
| `CLAUDE_CONFIG_DIR` | `~/.claude` | Claude CLI config directory. |
| `XI_THEME_MODE` | auto-detected | TUI color scheme override: `light` or `dark` (`xi.tui.theme-mode`). |

### Server / networking

| Variable | Default | Description |
| --- | --- | --- |
| `XI_PORT` | `7474` | WS server port (plain `ws://`, TUI + web). |
| `XI_TLS_PORT` | `7443` | HTTPS/`wss://` port (when certs exist). |
| `XI_TLS_CERT` | `~/.config/xi/tls/xi.crt` | TLS certificate path. Setting it (or `XI_TLS_KEY`) forces TLS on. |
| `XI_TLS_KEY` | `~/.config/xi/tls/xi.key` | TLS private key path. |
| `XI_ICON` | `desktop` (`personal` in personal-agent mode) | Icon variant served at `/apple-touch-icon.png` (e.g. `hetzner`). |

See [tls-https.md](tls-https.md) for the HTTPS/`wss://` setup.

### Providers

| Variable | Default | Description |
| --- | --- | --- |
| `XI_CLAUDE_RUNNER_PATH` | bundled `packages/providers/anthropic/runner.mjs` | Path to the Claude SDK runner script spawned per turn by `xi.providers.anthropic`. |
| `XI_CLAUDE_CLI_PATH` | `packages/providers/anthropic/claude/bin/claude` (built by the runner), else `claude` from `PATH` | Claude CLI executable used by the runner. The repo pins the CLI version in `packages/providers/anthropic/nix/`; bump with `bb claude:update`. Setting this skips the pinned build. |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama endpoint (`xi.providers.ollama`). |
| `OPENCODE_API_KEY` | — | OpenCode Zen API key (`xi.providers.zen`). Falls back to `OPENCODE_ZEN_API_KEY`, then `~/.local/share/opencode/auth.json`. Optional — free chat-completions models work without it. |
| `OPENCODE_ZEN_API_KEY` | — | Alternate name for the Zen API key. |
| `CODEX_HOME` | `~/.codex` | Directory holding the Codex CLI's `auth.json`, reused by the OpenAI Codex provider (`xi.providers.openai.codex`) for `openai/<model>` ids. Run `codex login` to populate it. |

See [providers-zen.md](providers-zen.md) for the OpenCode Zen provider and
[providers-openai.md](providers-openai.md) for the OpenAI Codex
(ChatGPT-subscription) provider — models, auth, and supported API surfaces.

### Extensions

| Variable | Default | Used by | Description |
| --- | --- | --- | --- |
| `PUSHOVER_USER_KEY` | — | pushover | Pushover user key; the extension is inert unless both keys are set. |
| `PUSHOVER_APP_TOKEN` | — | pushover | Pushover application token. |
| `PUSHOVER_URL` | — | pushover | Optional URL attached to the push. |
| `GITHUB_USER_SESSION` | — | github-code-search | GitHub session cookie for github.com code search. |
| `WINDOWID` | — | done-notify | Terminal window id used to focus on notification. |
| `XI_PRODUCT_SEARCH_CHROME` | auto-detected | product-search | Path to the Chrome/Chromium binary used to drive the `amazon_search` / `willhaben_search` / `geizhals_search` headless browser. Falls back to the legacy `XI_AMAZON_CHROME`, then common install paths and `google-chrome-stable` on `PATH`. |
| `XI_AMAZON_CHROME` | — | product-search | Legacy fallback for `XI_PRODUCT_SEARCH_CHROME`. |
| `XI_WEBSEARCH_SCRIPT` | `scripts/websearch.clj` | freesearch | Path to the babashka script behind the free `web_search` tool. |
| `GEMINI_API_KEY` | — | image-graph | Gemini API key; usually kept in `~/.config/xi/ext/image-graph.env`. |

Feature-specific variables are documented with their feature: `XI_CHROME_*`
(browser tools) in [chrome-mcp.md](chrome-mcp.md), `XI_TREESITTER_DIR` in
[treesitter.md](treesitter.md).

---

## On-disk config files

Some features persist runtime state under `~/.config/xi/`. These are not part of
the compile-time `config.cljc` — they are written and read at runtime.

### Extra system-prompt files

| Path | Read by | Description |
| --- | --- | --- |
| `~/.config/xi/prompt-files.edn` | `xi.system-prompt` | An EDN vector of markdown file paths (leading `~` expanded), e.g. `["~/.config/dotfiles/config/agent-instructions/xi.md"]`. Each existing, non-empty file is appended to the system prompt of **every** session on this machine, after AGENTS.md / profile / skill content. Missing config or files are silently skipped. Read fresh at room provisioning, so edits apply to the next session without a server restart. |

Use this for machine-local personal instructions that should not live in any
project's AGENTS.md — e.g. pointers to task recipe docs. Machines without the
file (servers, boxes) are unaffected.

### External MCP servers

| Path | Written by | Description |
| --- | --- | --- |
| `~/.config/xi/mcp.edn` | `/mcp` command | Registry of external MCP servers. A map of `server-id → entry`; each entry has `:transport` (`:stdio` or `:http`), the launch `:command`/`:args` (stdio) or `:url`/`:headers`/`:auth` (http), and optional `:enabled false` to keep a server registered but off. |
| `~/.config/xi/mcp/<id>/tools.edn` | `/mcp add`, `/mcp refresh` | Cached tool definitions for one server, so its tools can be advertised synchronously at boot without spawning the subprocess. Refreshed on `/mcp refresh`; the whole `~/.config/xi/mcp/<id>/` dir is removed on `/mcp remove`. |

Managing these files by hand is not required — use the `/mcp` command
(`add` / `refresh` / `enable` / `disable` / `remove` / `list`). Runtime
enable/disable of *any* extension (including the `mcp-<id>` wrappers) also
works via `/ext enable|disable`. See [mcp-servers.md](mcp-servers.md) for the
full walkthrough.

### clj tool (sandboxed Clojure) allowlist

| Path | Read by | Description |
| --- | --- | --- |
| `~/.config/xi/ext/clj.edn` | `xi.ext.clj` | Global config for the sandboxed `clj` scripting tool. An EDN map; `:allow-clis` is a vector of CLI binary names (e.g. `["ffmpeg" "jq"]`) that `(sh …)` may run without a confirm dialog, in every room. `:helper-hints` (default `true`) — set to `false` to stop appending "prefer the builtin helpers" hints to auto-run `(sh …)` results if they degrade model output (auto-run itself stays on). Read once per server process. |
| `~/.config/xi/ext/bb-trust.edn` | `xi.ext.clj` | Trust store for the `bb` tool. An EDN map `{:shas #{"<sha256>" …}}` of trusted `bb.edn` content hashes. When the nearest `bb.edn` (walking up from the room cwd) hashes to one of these, `bb <task>` (and `(sh "bb" …)`) run without a confirm dialog; editing `bb.edn` changes its sha and auto-revokes trust. Written at runtime by `/clj trust-bb` or the `bb` approval dialog's "always" answer — not hand-edited. See [clj-tool.md](clj-tool.md). |

Per-session (room) allowances are managed at runtime instead: approving a
confirm dialog with "always" or running `/clj allow <cli>` adds the binary to
the room's session allowlist (it dies with the room). See
[clj-tool.md](clj-tool.md).

### Per-extension secrets (gitignored)

| Path | Read by | Description |
| --- | --- | --- |
| `~/.config/xi/ext/<id>.env` | `xi.ext.config` | Dotenv-style per-extension secrets (`KEY=VALUE` lines; `#` comments and blank lines ignored; surrounding quotes stripped). Lives outside the repo; the repo `.gitignore` also covers stray `*.env` as a backstop. A non-blank `process.env` value of the same name overrides the file. |

Used for API keys that must not be committed. For example, the
disabled-by-default **Render** MCP extension reads its key from
`~/.config/xi/ext/render.env` (`RENDER_API_KEY=…`); the `mcp.edn` entry only
references the key by name via an `:auth` descriptor, never the secret itself.
See [mcp-servers.md](mcp-servers.md#http-transport-streamable-http--api-key).
