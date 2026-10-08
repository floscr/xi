# Compile-time configuration (`src/xi/config.cljc`)

User-facing configuration (`~/.config/xi/config.edn`, the other files under
`~/.config/xi`, environment variables) is documented in the guide:
[configuration](guide/configuration.md). This page covers what is compiled
into the build from `src/xi/config.cljc`: which extensions and providers
load per surface, the TUI display overrides, the web appearance defaults and
the feature flags. Editing it needs a recompile (the shadow watch picks it
up).

## Extension and provider loading (`server` · `client` · `web` · `providers`)

One vector per surface of extension maps or factory fns `(fn [ctx] → ext|nil)`,
composed in order by `ext/compose` at assembly. **Order matters**: handlers
chain in list order and a later extension's tool replaces an earlier one of
the same name.

- `server` (`:node`) — extensions whose state + provider/tool hooks run
  server-side (server, standalone, mirrored into clients).
- `client` (`:node`) — process-local extensions in the TUI client process.
- `web` (`:browser`) — browser-safe extension web halves.

The file is shared by every build via custom reader features
(`#?(:node …)` / `#?(:browser …)`, set per build in `shadow-cljs.edn`), so
node-only requires never reach the browser bundle. See
[extensions.md](extensions.md).

## TUI display options (`xi.config/tui`)

Only the keys to change from their default belong in the `tui` map; every
option's default lives in its consuming namespace, read via
`xi.config/tui-opt`. Options are declared at the call site with
`deftui-opt` (`xi.config-macros`, a `.clj` macro ns because a `.cljc` whose
requires sit behind reader features can't be loaded as a JVM macro ns):

```clojure
(deftui-opt my-option 42
  "What this option does. Overridable via :my-option in xi.config/tui.")
```

Document a new option in the `deftui-opt` docstring, in the `tui` map's
docstring, and in this table.

| Key | Default | Owner | Description |
| --- | --- | --- | --- |
| `:truncate-output-block-after-n-lines` | `100` | `xi.client.view` | Max tool-output lines rendered in a tool block before the rest collapses into a `... (N more lines)` marker. |

## Web appearance defaults (`xi.config/appearance`)

Defaults for the web client's collapsible blocks; the Appearance dialog
overrides them per user (the `:appearance` key of the user's state,
`xi.user-state`, mirrored to `localStorage "xi/appearance"`). Precedence:
built-in defaults (`xi.web.appearance`) ← this map ← the user's overrides.
Unknown keys and bad values are ignored at every layer.

| Key | Default | Description |
| --- | --- | --- |
| `:viewer-mode?` | `true` | Fold runs of tool / thinking posts into one grouped box of header rows. |
| `:super-collapsed?` | `false` | Fold a fully collapsed viewer group into one summary row. Needs `:viewer-mode?`. |
| `:tool-blocks` | `:collapsed` | `:open` or `:collapsed` for tool call details. A tool awaiting Allow/Deny is always open. |
| `:thinking-blocks` | `:collapsed` | `:open` or `:collapsed` for thinking blocks. |

## Feature flags

Plain `def`s read by their consuming namespace.

| Flag | Default | What it does | Owner |
| --- | --- | --- | --- |
| `quick-replies?` | `false` | After each finished turn, run a cheap model over the final message to detect a decision point and show one-tap quick-reply chips. A regex gate runs first so most turns never call the model. | `xi.quick-replies` |
| `recommend-rule?` | `false` | Adds a **Recommend a rule** option (`?`) to every `:ask` dialog: denies the call, spawns a sub-agent to draft a rule, opens an editable save dialog. Saving writes runtime scopes into app state and `repo`/`global` scopes into the rules file directly, not through a tool call. Never offered for `xi.api.*` calls. | `xi.ext.rules` via `xi.cli` |

## Where runtime config is read

| Namespace | Reads |
| --- | --- |
| `xi.user-config` | `~/.config/xi/config.edn` (`:xi/config`, version-locked, fails closed) |
| `xi.agent-profile` | `[:agents id]` of the user config, at every room provisioning / prompt run |
| `xi.projects` | `:projects` of the user config + `~/.config/xi/state/projects.edn` |
| `xi.rules.store` | `~/.config/xi/rules.edn`, `<repo>/.xi/rules.edn` (mtime-cached) |
| `xi.ext.mcp` | `~/.config/xi/mcp.edn`, `~/.config/xi/mcp/<id>/tools.edn` |
| `xi.ext.config` | `~/.config/xi/ext/<id>.env` (dotenv; a non-blank `process.env` value wins) |
| `xi.ext.clj` | `~/.config/xi/ext/clj.edn` (once per server process) |
| `xi.bb-trust`, `xi.mcp.trust` | `~/.config/xi/ext/bb-trust.edn`, `mcp-trust.edn` |
| `xi.system-prompt` | `~/.config/xi/prompt-files.edn` |
| `xi.auth` | `~/.config/xi/client-key`, `clients.edn`, `pending-clients.edn` |
| `xi.server.ws` (`resolve-tls`) | `XI_TLS_CERT`/`XI_TLS_KEY`, else `~/.config/xi/tls/xi.{crt,key}` |
| `xi.providers.fake` (via `xi.cli/providers`) | `XI_FAKE_LLM` (script file; when set, the scripted fake is the only provider, as `:anthropic`), `XI_FAKE_LLM_LOG` (JSONL of what each turn saw). Dev/test only, not user settings; see [testing.md](testing.md). |

`CLAUDE_CONFIG_DIR` is set internally (`xi.session/make-throwaway-config-dir!`)
to point the Claude CLI at a throwaway mirror for side turns and
`xi prompt --no-store`; it is not a user setting.
