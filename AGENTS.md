# Xi

Personal coding agent in ClojureScript + Bun.

## Processes: run `bb check` first

> **Before starting, stopping, restarting, or debugging ANY process** (compile,
> shadow-cljs watch, server), run `bb check`. It reports what's running where
> (ports 7474 main · 7476 demo · 8100 dev-http · 9630 shadow,
> bun servers, shadow watches, and the `xi` / `xi-serve` / `xi-demo`
> tmux sessions) and prints which `bb` task manages each. **Never start
> services by hand** (`bun target/main.js …`, `npx shadow-cljs …`) **and never
> `kill` them by PID** — that orphans processes outside tmux and has left stuck
> servers holding :7474 before. Only the `bb` tasks start/stop/restart.

- **A shadow-cljs watch is usually running** (`bb dev` / `bb serve`) and
  recompiles both the `main` and `web` targets on save. The two are exclusive
  (one shadow server can watch a build only once): starting or restarting
  either stops the other. If it is, don't run
  `bb build` / `bb web:build` (the latter can conflict with the watch).
  Otherwise use `bb build`, `bb web:build`, or start a watch.
- **The server does not hot-reload server-side code** (`xi.server.*`,
  `xi.core.*`, `xi.agent`, extensions, providers, …) — restart it with
  `bb serve:restart`. The web client hot-reloads; just refresh the page.
- **`bb serve:restart` / `bb serve:stop` sever your own connection** (they kill
  the server hosting your session). They're confirmed, then run detached
  (`xi.server-control`) and return success immediately; the WS link dropping right after is the
  expected sign it worked — do NOT retry. It's back in ~15s (`bb check`) —
  the server window waits for the fresh watch's first build
  (`bb serve:await-build`) so it never boots the previous build. If a
  restart seems risky, ask first.
- `bb tasks` lists everything (build, test, serve, demo, claude, treesitter, …).

## Testing

- `bb test` (once) / `bb test:watch`. Tests are `cljs.test` under `test/`,
  mirroring `src/` (`test/xi/commands_test.cljs` ↔ `src/xi/commands.cljs`);
  any `*_test.cljs` is auto-discovered. Prefer pure functions; avoid
  filesystem/network I/O.
- **Never run `xi` / `bun target/main.js` from the agent** — the TUI needs an
  interactive terminal. Compile and let the user test. `xi prompt "…"`
  (one-shot, no TUI) is safe.
- **Web UI self-tests use the isolated demo server** (`bb demo`, :7476,
  `HOME=.demo-home` with fake sessions) — not the real :7474 unless the user
  asks. Install the key from `bb demo:key` into `localStorage`
  (`xi-client-key`), reload, drive it via the chrome-devtools tools. See
  [docs/demo.md](docs/demo.md).

## Architecture in brief

Full picture: [docs/architecture.md](docs/architecture.md).

- **One state atom per process; everything is an event.** Handlers are pure
  `(fn [state event]) → {:state :effects} | nil`; side effects run only in the
  effect interpreter (`xi.core.app/create-app`). The WS protocol is the event
  maps themselves as transit (`xi.wire`).
- **Standalone = not connected.** Standalone, server and client modes share
  the same state shape and code paths; transports just forward events.
- **Extensions and providers are plain data maps**, declared per surface in
  `src/xi/config.cljc` and composed at assembly time — no registration atoms.
  See [docs/extensions.md](docs/extensions.md) (reference) and
  [docs/writing-extensions.md](docs/writing-extensions.md) (recipe).
- **Providers:** `anthropic` (Claude Agent SDK, out-of-process runner),
  `ollama`, `openai.codex` (`openai/<id>`, ChatGPT subscription), `zen`
  (`opencode/<id>`).
- Sessions: metadata in `~/.config/xi/sessions/`, transcripts in Claude CLI
  sessions under `~/.claude/projects/`; named-agent sessions (`--agent`,
  profiles in `~/.config/xi/config.edn`) in
  `~/.config/xi/personal-agent/<agent>/`. Auth: the Claude CLI's own login, or
  `ANTHROPIC_API_KEY`.

### The Claude SDK runner

The Agent SDK is not a Xi dependency: `packages/providers/anthropic/runner.mjs`
(own `package.json`) is spawned per turn and speaks NDJSON over stdio; every
tool call is proxied back to the host, so tools + rules stay host-side. The
host side (`xi.providers.runner`) is provider-agnostic. Details:
[docs/mcp-tool-bridge.md](docs/mcp-tool-bridge.md).

- **The Claude CLI is pinned in-repo via nix** (`packages/providers/anthropic/nix/`)
  and built by the runner itself. On "Claude Code X does not support this
  model": `bb claude:update` — applies next turn, no restart.
- **Token hygiene:** text-only side turns (titles, quick replies, summaries)
  must pass `:no-tools? true`, or each carries every tool definition (~25k
  tokens). Xi is the sole loader of project instructions — don't re-enable the
  CLI's own AGENTS.md loading.
- The runner's stderr paints over the TUI — real errors only, no per-turn logs.

## Repo map

```
src/xi/          cli (entry + assembly), config.cljc (extensions/providers/
                 options), core/ (state, events, app, log), agent, commands,
                 wire, session*, system_prompt, rules/, paths, tools/,
                 providers/, server/, client/ (TUI), tui/, web/ (browser),
                 ext/ (extensions), markdown/, highlight/, mcp/
test/xi/         mirrors src/
resources/       web client (public/), overlay scripts, grammars, chrome tools
scripts/         bb/node build helpers (overlay build, demo seed, codegen)
packages/        outside the shadow-cljs build: bb-client/ (JVM client lib),
                 providers/anthropic/ (SDK runner + pinned CLI),
                 xi-treesitter/ (native outline CLI)
docs/            see the index below
```

## Docs index

| Topic | Doc |
|---|---|
| Core loop, state, events, connection layer | [architecture.md](docs/architecture.md) |
| CLI commands, flags · config options + env vars | [cli.md](docs/cli.md) · [config.md](docs/config.md) |
| Server, rooms, HTTP API · client auth · TLS | [server.md](docs/server.md) · [client-auth.md](docs/client-auth.md) · [tls-https.md](docs/tls-https.md) |
| One-shot `xi prompt` · bb client lib | [prompt-mode.md](docs/prompt-mode.md) · [bb-client.md](docs/bb-client.md) |
| Slash commands · `/tree` · compaction · resume | [commands.md](docs/commands.md) · [session-tree.md](docs/session-tree.md) · [compaction.md](docs/compaction.md) · [resume.md](docs/resume.md) |
| Extensions · user extensions (runtime, sandboxed) · runtime toggling + external MCP | [extensions.md](docs/extensions.md) · [writing-extensions.md](docs/writing-extensions.md) · [user-extensions.md](docs/user-extensions.md) · [mcp-servers.md](docs/mcp-servers.md) |
| Rules / permissions · `clj` tool · tree-sitter reads | [rules.md](docs/rules.md) · [clj-tool.md](docs/clj-tool.md) · [treesitter.md](docs/treesitter.md) |
| Providers | [mcp-tool-bridge.md](docs/mcp-tool-bridge.md) · [providers-zen.md](docs/providers-zen.md) · [providers-openai.md](docs/providers-openai.md) |
| Browser tools | [chrome-mcp.md](docs/chrome-mcp.md) · [element-picker.md](docs/element-picker.md) · [design-mode.md](docs/design-mode.md) · [style-editor.md](docs/style-editor.md) |
| Web client · offline · UI components · demo | [web-client.md](docs/web-client.md) · [web-offline.md](docs/web-offline.md) · [frontend.md](docs/frontend.md) · [demo.md](docs/demo.md) |
| TUI rendering · syntax highlighting | [tui-rendering.md](docs/tui-rendering.md) · [syntax-highlighting.md](docs/syntax-highlighting.md) |
| Concurrent-edit safety (file hashes) · holds + cross-room git lock | [concurrent-edits.md](docs/concurrent-edits.md) · [git-lock.md](docs/git-lock.md) |

## Conventions

- Env vars: `(aget js/process.env "KEY")`, not property access.
- Async: JS promises via `(.then p f)` chains.
- Tool results: `{:content [{:type "text" :text "…"}] :is-error false}`.
- **Web UI: always use clj-ui-framework `ui.*` components** — never hand-roll
  `[:select]`/`[:input]`/`[:button]` when a component exists. See
  [docs/frontend.md](docs/frontend.md) and the section below.
- **Always document config options** in [docs/config.md](docs/config.md). An
  option's *default* lives in its consuming namespace; TUI options are
  declared with `deftui-opt` (`xi.config-macros`), e.g.
  `(deftui-opt truncate-output-block-after-n-lines 100 "doc…")`, which reads
  `xi.config/tui` with that fallback. Document each new option at the
  `deftui-opt` call site and in the `tui` map's docstring (key, effect,
  default, owning ns).

<!-- clj-ui-framework:begin -->
## UI Framework — clj-ui-framework

This repo uses the shared **clj-ui-framework** component library
(cross-target Clojure/ClojureScript/Squint UI components, theme
tokens, icons, and browser JS runtime), pinned as a git dependency
in this project's `bb.edn`/`deps.edn`.

- Remote (git dep source): <https://github.com/floscr/clj-ui-framework>

Before doing UI work here, read the framework's `AGENTS.md` — it
documents the available components and icons (full generated list in
`docs/components.md`), how to add new components and icons, per-target
pitfalls (hiccup/replicant/squint), theming/tokens, and the JS runtime.
Update the framework by bumping the pinned `:sha` in this project's
`bb.edn`/`deps.edn`.
<!-- clj-ui-framework:end -->
