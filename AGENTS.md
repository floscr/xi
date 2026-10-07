# Xi

Personal coding harness in ClojureScript + Bun.

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

- `bb test` (once) / `bb test:watch`. `bb test` prints one line when all
  pass, or only the failing tests; `bb test --full` shows the raw output.
  Tests are `cljs.test` under `test/`,
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
resources/       web client (public/), syntax grammars, treesitter/ (WASM
                 runtime + grammars for outlines)
bin/             `xi` launcher (the npm bin; hands over to bun)
scripts/         bb/node build helpers (demo seed, codegen, treesitter WASM,
                 build-package.mjs → `bb package`)
packages/        outside xi's own bundle: bb-client/ (JVM client lib),
                 providers/anthropic/ (SDK runner + pinned CLI),
                 mcp-bb-example/ (babashka MCP server example)
docs/            see the index below; docs/guide/ is the user guide the site renders
site/            home page + docs site (own bb.edn; `bb site:dev` / `bb site:build`
                 from the root render docs/guide/*.md into site/dist). UI is
                 clj-ui-framework: buttons, cards, theme toggle, tokens and the
                 light/dark/system theme (site/src/xisite/theme.clj); main.css only
                 holds site layout and uses the framework's tokens
```

## Docs index

| Topic | Doc |
|---|---|
| Core loop, state, events, connection layer | [architecture.md](docs/architecture.md) |
| Compile-time config (`config.cljc`: extension/provider vectors, TUI opts, appearance, flags) | [config.md](docs/config.md) |
| Built-in extensions: every key, dialogs, hooks, web halves · recipe for a new one | [extensions.md](docs/extensions.md) · [writing-extensions.md](docs/writing-extensions.md) |
| MCP client internals (manager, trust fingerprint, wire protocol) · tree-sitter reads | [mcp-internals.md](docs/mcp-internals.md) · [treesitter.md](docs/treesitter.md) |
| Providers (map shape, adapters, Zen/Codex internals) · the Claude SDK runner | [providers.md](docs/providers.md) · [mcp-tool-bridge.md](docs/mcp-tool-bridge.md) |
| Web client internals · offline cache · UI components · demo | [web-client-internals.md](docs/web-client-internals.md) · [web-offline.md](docs/web-offline.md) · [frontend.md](docs/frontend.md) · [demo.md](docs/demo.md) |
| TUI rendering · syntax highlighting | [tui-rendering.md](docs/tui-rendering.md) · [syntax-highlighting.md](docs/syntax-highlighting.md) |
| npm package layout · `bb package` · `bb package:serve` | [packaging.md](docs/packaging.md) |
| Concurrent-edit safety (file hashes) · holds + cross-room git lock | [concurrent-edits.md](docs/concurrent-edits.md) · [git-lock.md](docs/git-lock.md) |
| **User guide** — THE user documentation (CLI, config, rules, clj tool, MCP, extensions, server, web client, models, sessions, prompt mode, bb client). Every user-facing option is documented there, nowhere else. Navigation = the Pages list in its README | [docs/guide/](docs/guide/README.md) |
| Home page + docs site (bb, hiccup, markdown-clj; `bb site:dev` on :4322) | [site/](site/) |

## Conventions

- Env vars: `(aget js/process.env "KEY")`, not property access.
- Async: JS promises via `(.then p f)` chains.
- Tool results: `{:content [{:type "text" :text "…"}] :is-error false}`.
- **Web UI: always use clj-ui-framework `ui.*` components** — never hand-roll
  `[:select]`/`[:input]`/`[:button]` when a component exists. See
  [docs/frontend.md](docs/frontend.md) and the section below.
- **Always document options.** User-facing ones (`config.edn` keys, files
  under `~/.config/xi`, env vars, flags, rule fields, clj helpers) go in the
  guide's reference pages ([docs/guide/configuration.md](docs/guide/configuration.md),
  `command-line.md`, `rules-reference.md`, `clj-tool-reference.md`,
  `extensions-reference.md`); compile-time ones in [docs/config.md](docs/config.md). An
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
