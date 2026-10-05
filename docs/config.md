# Configuration

Xi has two configuration surfaces:

1. **`src/xi/config.cljc`** — compile-time config: which extensions load on each
   surface, the `tui` display-override map and the web `appearance` map.
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

## Web appearance options (`xi.config/appearance`)

How the web client's chat timeline renders its collapsible blocks. Same
pattern as `tui`: the map holds only the keys you want to change, and the
defaults live in `xi.web.appearance`. Each browser can override these from the
**Appearance** dialog (sidebar footer gear, chat overflow menu → "Appearance",
or the command palette); those overrides are stored per device in
`localStorage` (`xi/appearance`), and "Reset to defaults" drops them, landing
back on this map.

```clojure
(def appearance
  {:viewer-mode? false
   :tool-blocks  :open})
```

| Key | Default | Description |
| --- | --- | --- |
| `:viewer-mode?` | `true` | Fold each run of consecutive tool / thinking posts into one grouped box of header rows. |
| `:super-collapsed?` | `false` | Fold every viewer group whose blocks are all collapsed into one summary row (step count + latest block). Needs `:viewer-mode?`; a group holding an open block stays unfolded. |
| `:tool-blocks` | `:collapsed` | `:open` or `:collapsed` — whether a tool call's details (arguments, result) start expanded. A tool awaiting an Allow/Deny answer is always open. |
| `:thinking-blocks` | `:collapsed` | `:open` or `:collapsed` — whether thinking blocks start expanded. |

Precedence: built-in defaults ← `xi.config/appearance` ← the browser's own
overrides. Unknown keys and disallowed values are ignored at every layer. See
[web-client.md](web-client.md#chat-view-chatsession-id).

---

## Extension loading (`xi.config/server` · `client` · `web`)

`config.cljc` also declares which extensions load per surface. Each is a vector
of extension maps or factory fns `(fn [ctx] → ext|nil)`, composed in order by
`ext/compose` at assembly time. **Order matters** — handlers chain in list
order, and a later extension's tool replaces an earlier one of the same name.

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
| `recommend-rule?` | `false` (disabled) | Adds a **Recommend a rule** option (key `?`) to every `:ask` rule's confirm dialog: it denies the call, spawns a background sub-agent to draft a rule for it, and opens an editable save dialog with the result. See [rules.md](rules.md#recommend-a-rule-from-a-guard-dialog). Set to `true` to enable. Applies to tool calls decided via `xi.cli`'s tool-policy; `xi.api.*` calls from user extensions never offer it. | `xi.ext.rules` (via `xi.cli`) |

---

## Environment variables

### Core / model

| Variable | Default | Description |
| --- | --- | --- |
| `XI_CWD` | `process.cwd()` | Working directory for the room. |
| `ANTHROPIC_API_KEY` | — | API auth, passed through to the Claude CLI (otherwise the CLI's own login is used). |
| `XI_THEME_MODE` | unset | TUI color scheme override: `light` or `dark` (`xi.tui.theme-mode`). Wins over the `:theme/set` event; without either the TUI uses `dark`. To follow an OS or terminal theme switcher live, dispatch `:theme/set` from a [user extension](user-extensions.md#following-the-system-theme). |

`CLAUDE_CONFIG_DIR` is not a Xi setting: Xi sets it internally to point the
Claude CLI at a throwaway config mirror for side turns (titles, summaries,
quick replies, sub-agents) and `xi prompt --no-store`, so their transcripts
never reach the session list (`xi.session/make-throwaway-config-dir!`).

### Server / networking

| Variable | Default | Description |
| --- | --- | --- |
| `XI_PORT` | `7474` | WS port (plain `ws://`, TUI + web) when `--port` is omitted — in every mode: the server binds it, `join`/`create`/`sessions` and standalone auto-join connect to it, `/browser-open` opens it. |
| `XI_HOST` | `127.0.0.1` | Address the server binds (both the `ws://` and the TLS listener) when `--host` is omitted. Loopback by default; set `0.0.0.0` (all interfaces) or a specific address (e.g. a Tailscale IP) to accept remote clients. See [server.md](server.md#bind-address). |
| `XI_TLS_PORT` | `7443` | HTTPS/`wss://` port (when certs exist). |
| `XI_TLS_CERT` | `~/.config/xi/tls/xi.crt` | TLS certificate path. Setting it (or `XI_TLS_KEY`) forces TLS on. |
| `XI_TLS_KEY` | `~/.config/xi/tls/xi.key` | TLS private key path. |
| `XI_ICON` | `desktop` (`personal` on an agent server) | Icon variant served at `/apple-touch-icon.png`: `desktop`, `personal` or `green`. |
| `XI_PUBLIC_HOST` | Tailscale IPv4 if `XI_HOST` is set to a non-loopback address and `tailscale` is installed and up, else `localhost` | Host shown in the `Web: http://…` URL printed by `bb serve`, `bb serve:restart`, `bb serve:personal*` and `bb dev:url`. Cosmetic only — `XI_HOST` decides what the server binds. Read by the `host-ip` helper in `bb.edn`. |

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
| `XI_GIT_LOCK_WAIT_SECS` | `600` | `xi.git-lock` (git-index hold) | How long a room waits for another room's git-index hold before its git op fails with an error naming the holder. See [git-lock.md](git-lock.md). |

Feature-specific variables are documented with their feature: `XI_TREESITTER_DIR`
in [treesitter.md](treesitter.md). An MCP server's own variables go in its
`mcp.edn` entry's `:env` ([mcp-servers.md](mcp-servers.md)).

---

## On-disk config files

Some features persist runtime state under `~/.config/xi/`. These are not part of
the compile-time `config.cljc` — they are written and read at runtime.

### User config (`~/.config/xi/config.edn`)

The user config file (`xi.user-config`). An EDN map tagged `:type :xi/config`
with a required `:version` (currently `1`), exactly like `rules.edn`
(`:xi/rules`):

```clojure
{:type       :xi/config
 :version    1
 :extensions ["kb.cljs" "freesearch.cljs" "web.cljs"]
 :agents     {"root" {:system-prompt-file "agents/root.md"
                      :model "claude-sonnet-4-6"
                      :extensions ["freesearch.cljs" "web.cljs"]
                      :tools ["web_search" "fetch"]}}
 :projects   {:browse ["~/Code/Projects" {:dir "~/Code/Work" :depth 2}]
              :repos  ["~/.config/dotfiles"]}
 :trusted-mcp-servers ["chrome" "product-search/browser"]}
```

| Key | Description |
| --- | --- |
| `:extensions` | The [user extensions](user-extensions.md#enabling) xi may load: file names in `~/.config/xi/extensions/`. The only place that can enable one. |
| `:agents` | Agent profiles, id → profile, for `xi --agent <id>`, `xi server --agent <id>` and `xi prompt --agent <id>` (`xi.agent-profile`). Keys below. |
| `:projects` | The project directories behind `/project`, Alt+P and the web projects page (`xi.projects`). [Keys below](#projects). |
| `:trusted-mcp-servers` | MCP servers whose calls never ask: `mcp.edn` ids, or an extension's `"<extension>/<name>"` (`xi.mcp.trust`). Trusted as they are, with no code fingerprint: the config is the decision. Servers not listed are trusted at runtime instead (`[a]lways`, `/mcp trust`), until their code changes. See [mcp-servers.md](mcp-servers.md#trusting-a-server-once-until-its-code-changes). |

**An invalid file fails closed**: a missing or wrong `:type`, a missing or
unsupported `:version`, an unknown top-level key or unparseable EDN enables
no extensions and makes every profile load as missing — no tools — with the
problem reported on stderr. A missing file is not an error (nothing is
enabled, profiles are simply absent).

#### Agent profiles

Profile keys:

| Key | Default | Description |
| --- | --- | --- |
| `:tools` | none | Vector of tool names the model is given (builtins and extension tools alike), or `:all`. Unlisted tools are never advertised. A missing key or a missing profile yields **no tools** (fail closed). |
| `:extensions` | the top-level list | Vector of user-extension file names this agent loads instead of the file's top-level `:extensions` (`[]` = none). Process-wide; `/ext reload` re-reads it. |
| `:system-prompt` | generic assistant prompt | Prompt text; replaces AGENTS.md, skills, extension prompts and `prompt-files.edn`. |
| `:system-prompt-file` | — | File holding the prompt; `~` expanded, relative paths resolve against `~/.config/xi/`. `:system-prompt` wins over it. |
| `:model` | — | Default model for the agent; a `--model` flag wins. |

Read at every room provisioning (server) or run (prompt), so edits apply to
the next chat without a restart. The file lives under `~/.config/xi`, a path
agents can never write, so only the operator decides an agent's tools. Full
semantics in [cli.md](cli.md#agent-profiles).

#### Projects

Xi builds its project list itself — no external `project` CLI. Three sources,
deduplicated, existing directories only:

| Source | Where it is defined | What it is |
| --- | --- | --- |
| `:repos` | `:projects` in `config.edn` | Single directories, listed as-is. |
| `:browse` | `:projects` in `config.edn` | Directories scanned for repos. |
| remembered | automatic, in `~/.config/xi/state/projects.edn` | A git repo a session ran in that neither of the above covers. Newest first, at most `:remember-limit`. |

**Remembering is automatic.** There is no command to add a project: creating a
room or `/cd`-ing anywhere inside a git repo records that repo's root (the
nearest enclosing directory with a `.git`, never `$HOME` or `/`), so a session
in `repo/src/deep` remembers `repo`. A directory outside any repo is not
remembered. The record lives in the state file, not `config.edn`, because the
config is often generated and read-only. To list a directory permanently, put it
in `:repos` or under a `:browse` directory.

The list is ordered by last visit; never-visited projects keep the order of the
table above. Visits are recorded for every session directory (the repo root, or
the directory itself outside a repo) — for a configured project that is what
moves it up the list.

`:projects` keys:

| Key | Default | Description |
| --- | --- | --- |
| `:browse` | `[]` | Vector of directories to scan. An entry is a path string (= `{:dir path}`) or `{:dir path :depth n :git? bool}`. |
| `:browse` `:depth` | `1` | How many levels below `:dir` to look (1–`xi.projects/max-depth`, 6). `1` = direct children only. |
| `:browse` `:git?` | `true` | `true`: a directory is a project when it contains `.git` (file or directory, so worktrees count); repos are never descended into, other directories are until `:depth` runs out. `false`: every directory down to `:depth` is a project. Hidden directories and `node_modules` are never scanned. |
| `:repos` | `[]` | Vector of directories listed regardless of whether they are repos. |
| `:remember-limit` | `50` | How many remembered repos (visited, but outside `:repos` / `:browse`) to list. `0` stops remembering. Visit times of listed projects are still kept for ordering. |
| `:settings` | `{}` | Per-project prompt and snippets, keyed by project directory (see below). |

**Per-project settings.** `:settings` maps a directory to the extras a chat in
that directory gets. The key must equal the room's cwd (`~` expanded, trailing
slash ignored) — sub-directories don't inherit.

```clojure
:settings {"~/Code/Projects/xi"
           {:agents-prompt  "docs/agents.md"   ; file, or the prompt text itself
            :agents-replace true               ; stands in for the project's AGENTS.md
            :snippets [{:label "Run checks" :text "Run `bb check`, then `bb test`."}]}}
```

| Key | Description |
| --- | --- |
| `:agents-prompt` | Appended to the system prompt after the project's AGENTS.md files. Read as a file when one exists at that path — relative to the project directory, else absolute — otherwise used as the prompt text. |
| `:agents-replace` | `true`: the prompt replaces the project root's own AGENTS.md / CLAUDE.md (parent directories' files still load). Default `false`. |
| `:snippets` | Vector of `{:label :text}` maps, offered in the web client's snippets menu in that project's chats only (global snippets: `~/.config/xi/snippets.edn`). |

Paths accept `~`. Missing directories are skipped, so one config can be shared
between machines. A bad `:projects` value makes the whole file invalid (see
above).

The state file is `{:version 1 :visits {path epoch-ms}}`; Xi writes it
atomically and keeps the newest 500 visits. Delete it to forget the remembered
repos and the recent-use order. Programmatic access: `xi.projects/visit!`,
`list-projects!`.

### Extra system-prompt files

| Path | Read by | Description |
| --- | --- | --- |
| `~/.config/xi/prompt-files.edn` | `xi.system-prompt` | An EDN vector of markdown file paths (leading `~` expanded), e.g. `["~/notes/xi-instructions.md"]`. Each existing, non-empty file is appended to the system prompt of **every** session on this machine, after AGENTS.md / profile / skill content. Missing config or files are silently skipped. Read fresh at room provisioning, so edits apply to the next session without a server restart. |

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
| `~/.config/xi/ext/bb-trust.edn` | `xi.bb-trust` | Trust store for the `bb` tool. An EDN map `{:shas #{"<sha256>" …}}` of trusted `bb.edn` content hashes. When the nearest `bb.edn` (walking up from the room cwd) hashes to one of these, `bb <task>` (and `(sh "bb" …)`) run without a confirm dialog; editing `bb.edn` changes its sha and auto-revokes trust. Written at runtime by `/clj trust-bb` or the `bb` approval dialog's "always" answer — not hand-edited. See [clj-tool.md](clj-tool.md). |
| `~/.config/xi/ext/mcp-trust.edn` | `xi.mcp.trust` | Trust store for MCP servers. An EDN map `{:servers {"<id>" "<sha256>"}}`: one fingerprint per trusted server, over its `mcp.edn` entry and its code. A trusted server's tool calls run without a confirm dialog until the fingerprint changes. Written at runtime by `/mcp trust <id>` or the MCP approval dialog's "always" answer — not hand-edited. See [mcp-servers.md](mcp-servers.md#trusting-a-server-once-until-its-code-changes). |

Per-session (room) allowances are managed at runtime instead: approving a
confirm dialog with "always" or running `/clj allow <cli>` adds the binary to
the room's session allowlist (it dies with the room). See
[clj-tool.md](clj-tool.md).

### Per-extension secrets (gitignored)

| Path | Read by | Description |
| --- | --- | --- |
| `~/.config/xi/ext/<id>.env` | `xi.ext.config` | Dotenv-style per-extension secrets (`KEY=VALUE` lines; `#` comments and blank lines ignored; surrounding quotes stripped). Lives outside the repo; the repo `.gitignore` also covers stray `*.env` as a backstop. A non-blank `process.env` value of the same name overrides the file. |

Used for API keys that must not be committed. For example, a hosted MCP server
like Render reads its key from `~/.config/xi/ext/render.env`
(`RENDER_API_KEY=…`); the `mcp.edn` entry only references the key by name via
an `:auth` descriptor, never the secret itself.
See [mcp-servers.md](mcp-servers.md#http-transport-streamable-http--api-key).
