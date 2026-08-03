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

## Environment variables

### Core / model

| Variable | Default | Description |
| --- | --- | --- |
| `XI_MODEL` | `claude-opus-4-8` | Default model id. |
| `XI_EFFORT` | `high` | Reasoning effort. |
| `XI_CWD` | `process.cwd()` | Working directory for the room. |
| `XI_RELOAD_SESSION` | — | Session id to reload on start. |
| `ANTHROPIC_API_KEY` | — | API auth (alternative to `~/.pi/agent/auth.json` OAuth tokens). |
| `CLAUDE_CONFIG_DIR` | `~/.claude` | Claude CLI config directory. |

### Server / networking

| Variable | Default | Description |
| --- | --- | --- |
| `XI_PORT` | `7474` | WS server port (plain `ws://`, TUI + web). |
| `XI_TLS_PORT` | `7443` | HTTPS/`wss://` port (when certs exist). |
| `XI_TLS_CERT` | `~/.config/xi/tls/xi.crt` | TLS certificate path. Setting it (or `XI_TLS_KEY`) forces TLS on. |
| `XI_TLS_KEY` | `~/.config/xi/tls/xi.key` | TLS private key path. |

See [tls-https.md](tls-https.md) for the HTTPS/`wss://` setup.

### Providers

| Variable | Default | Description |
| --- | --- | --- |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama endpoint (`xi.provider.ollama`). |

### Extensions

| Variable | Default | Used by | Description |
| --- | --- | --- | --- |
| `PUSHOVER_USER_KEY` | — | pushover | Pushover user key; the extension is inert unless both keys are set. |
| `PUSHOVER_APP_TOKEN` | — | pushover | Pushover application token. |
| `PUSHOVER_URL` | — | pushover | Optional URL attached to the push. |
| `GITHUB_USER_SESSION` | — | github-code-search | GitHub session cookie for github.com code search. |
| `ORG_CLI_DIR` | `~/Code/Projects/org-mode-agenda-cli` | gtd, todo-intercept | Path to the org-mode agenda CLI. |
| `WINDOWID` | — | done-notify | Terminal window id used to focus on notification. |
| `XI_AMAZON_CHROME` | auto-detected | amazon | Path to the Chrome/Chromium binary used to drive the `amazon_search` headless browser. Falls back to common install paths and `google-chrome-stable` on `PATH`. |

---

## On-disk config files

Some features persist runtime state under `~/.config/xi/`. These are not part of
the compile-time `config.cljc` — they are written and read at runtime.

### External MCP servers

| Path | Written by | Description |
| --- | --- | --- |
| `~/.config/xi/mcp.edn` | `/mcp` command | Registry of external MCP servers. A map of `server-id → entry`; each entry has `:transport` (`:stdio`), the launch `:command`/`:args`, and optional `:enabled false` to keep a server registered but off. |
| `~/.config/xi/mcp/<id>/tools.edn` | `/mcp add`, `/mcp refresh` | Cached tool definitions for one server, so its tools can be advertised synchronously at boot without spawning the subprocess. Refreshed on `/mcp refresh`; the whole `~/.config/xi/mcp/<id>/` dir is removed on `/mcp remove`. |

Managing these files by hand is not required — use the `/mcp` command
(`add` / `refresh` / `enable` / `disable` / `remove` / `list`). Runtime
enable/disable of *any* extension (including the `mcp-<id>` wrappers) also
works via `/ext enable|disable`. See [mcp-servers.md](mcp-servers.md) for the
full walkthrough.
