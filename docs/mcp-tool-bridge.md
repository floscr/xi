# MCP Tool Bridge (SDK Runner)

Xi uses the Claude Agent SDK to talk to Claude Code (CC), but **CC never
executes tools directly**. The SDK runs in a separate **runner process**
(`packages/providers/anthropic/runner.mjs`, its own `node_modules`, freely upgradable SDK); the
runner exposes Xi's tools to CC via an in-process MCP server and **proxies
every tool call back to the host** over stdio. The host executes the call
through its own pipeline — including a tool gate that can block or rewrite
dangerous operations — so the tool registry and rules/permission gate never
leave the Xi process.

## Architecture

```
┌──────────────────────────────────────────────────────┐
│  Claude Code (subprocess of the runner, via SDK)     │
│                                                      │
│  Built-in tools: DISABLED (tools: [] whitelist)      │
│  Available tools: mcp__xi-tools__* only              │
│                                                      │
│  CC proposes: mcp__xi-tools__bash {command: "ls"}    │
└──────────────┬───────────────────────────────────────┘
               │ MCP call (in-process in the runner)
               ▼
┌──────────────────────────────────────────────────────┐
│  Runner (packages/providers/anthropic/runner.mjs, spawned per turn)        │
│                                                      │
│  · builds the SDK MCP server from the host's         │
│    toolDefs (JSON Schema → Zod)                      │
│  · forwards every SDK message to the host            │
│    as a `message` frame                          │
│  · proxies each tool call as a `tool-call` frame     │
│    and waits for the `tool-result` frame             │
└──────────────┬───────────────────────────────────────┘
               │ newline-delimited JSON over stdio
               ▼
┌──────────────────────────────────────────────────────┐
│  Xi host (providers/anthropic.cljs)                  │
│                                                      │
│  1. Receive tool-call frame from the runner          │
│  2. Run :tool-gate chain (extension-composed)        │
│  3. nil → "Blocked by Xi permission gate" error      │
│     {:intercepted true :result …} → return result    │
│     tool-call → execute via Xi tool registry         │
│  4. Send tool-result frame back to the runner        │
└──────────────────────────────────────────────────────┘
```

## Wire protocol (host ⇄ runner)

Newline-delimited JSON over the runner's stdin/stdout (stderr is inherited
for diagnostics). One runner process per turn — no cross-room interleaving.

Host → runner:

| Frame | Meaning |
|---|---|
| `{type:"start", queryOpts, envOverride, toolDefs, prompt, noTools}` | begin the turn |
| `{type:"tool-result", id, result:{content, isError}}` | answer a proxied tool call |
| `{type:"abort"}` | interrupt the turn gracefully |

Runner → host:

| Frame | Meaning |
|---|---|
| `{type:"message", message}` | one SDK message, decoded host-side by `process-sdk-message` |
| `{type:"tool-call", id, name, arguments}` | proxied tool call awaiting a `tool-result` |
| `{type:"done"}` | terminal: turn complete |
| `{type:"error", message}` | terminal: turn failed |

The runner guarantees a **single terminal frame** (`done` or `error`); the
host ignores any frame after it.

## Key files

| File | Role |
|------|------|
| `packages/providers/anthropic/runner.mjs` | SDK integration: query lifecycle, MCP server, tool-call proxying |
| `packages/providers/anthropic/package.json` | pins the SDK version — upgrade here; the runner reinstalls when the lockfile changes |
| `packages/providers/anthropic/nix/` | pins the Claude CLI release; the runner builds it to the `claude` out-link — see [The pinned CLI](#the-pinned-cli) |
| `src/xi/providers/runner.cljs` | host side of the runner protocol, provider-agnostic: spawn, framing, tool-call proxying, terminal frame |
| `src/xi/providers/anthropic.cljs` | Claude-specific: query options, SDK message decoding, tool gate + registry wiring |
| `src/xi/tools/registry.cljs` | Tool definitions and execute fns |
| `src/xi/tools/*.cljs` | Individual tools (bash, read, write, edit, grep, find, ls) |
| `src/xi/ext/core.cljs` | Tool-gate chain composition (`compose-tool-gate`) |
| `src/xi/ext/permission_gate.cljs` | Permission gate extension (blocks dangerous ops) |

## How it works

### 1. CC's built-in tools are disabled

CC is started with `permissionMode: "bypassPermissions"` (so it doesn't
prompt on stdin) and a **whitelist**: `tools: []` disables every built-in,
`allowedTools: ["mcp__xi-tools__*"]` exposes only Xi's MCP tools
(`base-query-opts` in `providers/anthropic.cljs`).

### 2. Xi's tools are exposed via MCP — in the runner

The host resolves the turn's tool surface with `resolve-tooling`
(extension extras/removals, personal-agent filter) and ships the ordered
`toolDefs` in the `start` frame. The runner builds an in-process MCP server
from them (`createSdkMcpServer`), converting each JSON Schema to Zod. Each
tool's handler doesn't execute anything — it emits a `tool-call` frame and
resolves when the matching `tool-result` arrives.

Extensions extend the tool surface per assembly via
`:extra-tool-definitions` / `:extra-tool-registry` (e.g. `kb_*`,
`web_search` — see [extensions.md](extensions.md)). The tooling seam is
fn-valued and deref'd fresh each turn, so `/ext enable|disable` takes
effect on the next turn.

In personal-agent mode (`:personal-agent?`), the definitions are filtered
to `PERSONAL_AGENT_TOOLS` (`web_search` only).

### 3. The tool gate (host-side)

The gate is an async transform chain composed from extensions at assembly
time (`ext/compose`) and passed into the provider per turn as `:tool-gate`.
Each link is `(fn [tool-call ctx]) → promise` of:

| Return | Meaning |
|---|---|
| tool-call (possibly modified) | allow / rewrite, continue the chain |
| `nil` | block — CC sees "Blocked by Xi permission gate" |
| `{:intercepted true :result …}` | short-circuit with a synthetic result |

Gate ctx provides `{:dispatch! :get-state :room-id :cwd :confirm!}` —
`confirm!` raises a dialog in the connected clients (TUI/web) and resolves
with the answer, which is how the permission gate and `/commit` confirm
work.

## Permission gate & rules engine

Tool-call policy (allow / deny / nudge / ask / confirm) is driven by the
declarative **rules engine** (`xi.ext.rules` + `xi.rules.store` /
`xi.rules`) — see [rules.md](rules.md) for the full reference. In precedence
order: an immutable hard-block (agents can never write the rules files), a
hardened tier (`sudo`, remote-copy shells, …), repo/global config
(`.xi/rules.edn`, `~/.config/xi/rules.edn`), runtime session rules, then the
built-in defaults. Highlights the defaults still enforce:

- **Credential paths** (`~/.ssh`, `~/.gnupg`, `~/.password-store`, …) —
  hard-blocked for read/write (`:credential` matcher, symlink-canonicalized).
- **Writes/edits outside the project repo**: the default outside-write rule
  (`{:match {:tool #{:write :edit} :outside :cwd} :action {:type :ask …}}`)
  confirms any `write`/`edit` whose path resolves outside the working dir and
  the OS tmp dir. The confirm offers `[r]` *allow repo* when the target sits
  inside another git repo, which persists a repo-scoped session allow-rule
  (`{:match {:tool #{:write :edit} :repo <root>} :action {:type :allow}}`) in
  the shared rules store — visible to `/rules`, not a private allowlist.

The permission-gate extension is gone. Its last pieces:

- **Guarded patterns** — `xi.rules.defaults/guarded-patterns`, the same list
  the `bash-guards` rule is built from; the `clj` gate confirms them for
  scanned `(sh …)` commands.
- **Server control** (`bb serve:restart` / `serve:stop`) — asked by the
  `server-control` default rule; the executors (bash, the bb tool, clj `sh`)
  run an approved one detached via `xi.server-control`.

To add a policy, add a rule (`.xi/rules.edn`, `/rules`, or a default in
`xi.rules.defaults`).

## Claude CLI resolution (NixOS)

The SDK ships a native, generically-linked CC binary that can't run on NixOS
(wrong `ld-linux`). The runner's `resolveClaudeExecutable()` instead resolves,
in order: `XI_CLAUDE_CLI_PATH`, the pinned `packages/providers/anthropic/claude/bin/claude`
out-link, then `claude` from `PATH` (following the symlink with
`realpathSync`), and passes the result as `pathToClaudeCodeExecutable`.

### The pinned CLI

The CLI gates new model ids on its own version ("Claude Code X does not
support this model; version Y or newer is required") and nixpkgs lags
upstream, so the release is pinned next to the runner:

| File | Role |
|------|------|
| `packages/providers/anthropic/nix/claude-code-manifest.json` | the upstream release manifest — this *is* the pin |
| `packages/providers/anthropic/nix/flake.nix` + `flake.lock` | nixpkgs' `claude-code` built with that manifest |
| `packages/providers/anthropic/claude` | out-link to the build (gitignored) |

Nothing is built by hand and there is no dev shell to enter: before each turn
the runner compares the manifest's version with the version the out-link
points at, and runs `nix build path:…/nix#claude-code -o …/claude` when they
differ or the link is missing. When they match this costs a file read. Without
`nix` on `PATH`, or when the build fails (offline), it uses whatever is already
linked or on `PATH`.

It builds once rather than using `nix run` per turn: the SDK needs an
executable path, and `nix run` would re-evaluate the flake on every turn and
side turn. The flake sits in its own directory and is used as a `path:` flake so
that evaluating it copies three small files into the store rather than
`node_modules` or the repo, and so it works on hosts without a git checkout.

`bb claude:update [version]` bumps the manifest to the latest release;
`bb claude:build` forces a build. Both take effect on the next turn.

## Prompt caching & token hygiene

Prompt caching is done by the Claude CLI (it places the `cache_control`
breakpoints, 1h TTL on a subscription). Xi's job is to keep the request prefix
— tools → system prompt → messages — byte-stable between turns and free of
dead weight. The rules:

- **The system prompt is built once per room**, at creation or cwd change
  (`system-prompt/load-agents-parts` + extension prompts), not per turn. An
  AGENTS.md edit mid-session therefore does not invalidate the cache; it is
  picked up by the next room / server restart.
- **Xi is the sole loader of project instructions.** `base-query-opts` passes
  `settingSources ["user"]`, so the CLI loads `~/.claude/CLAUDE.md` but not the
  project's `AGENTS.md`/`CLAUDE.md` — those are already in the appended system
  prompt (`find-agents-md` takes `AGENTS.md`, falling back to `CLAUDE.md`).
  With `settingSources` omitted the CLI loads everything and each session
  carries AGENTS.md twice (~7k tokens for this repo), plus a full re-injected
  copy after every edit.
- **Text-only side turns pass `:no-tools? true`** (titles, quick replies,
  summaries). That drops the tool bridge and the Claude Code preset prompt, and
  sets `settingSources []`. Measured on the pinned CLI, a no-tools turn is ~880
  prompt tokens instead of ~9k with the CLI loading instruction files; leaving
  the tool bridge on adds the tools + preset block on top (~25k tokens in
  session transcripts). Any new throwaway turn must do the same.
- Turns that **resume** a session (`/compact`, `/rollover`) keep the tools:
  their history contains tool calls. They run on a different model than the
  conversation, so they never hit its cache — that is a per-use cost of those
  commands.

Expected, unavoidable full rewrites of the cached prefix: switching the model
(caches are per model), a CLI upgrade (`bb claude:update`), a tool list change
(enabling/disabling an extension or MCP server), a server restart after the
system prompt's sources changed, and more than an hour of idle time.

To audit, read the `usage` of each assistant message in the session transcript
(`~/.claude/projects/<cwd>/<session>.jsonl`): at the first request of a turn,
`cache_read_input_tokens` should be ~95%+ of the prompt. A turn start whose
`cache_creation_input_tokens` is about the size of the whole conversation means
something in the prefix changed.

## Session resume

There is no provider-side session atom. The provider session id lives in
app state at `[:rooms room-id :session :provider-session-id]` — set by
`:agent/turn-end`, passed to the next turn as `:resume-session-id` (SDK
`resume` option). Clearing it (e.g. `/clear`, `/tree` navigation) makes the
next turn start a fresh Claude session with a new JSONL file.

## Stream processing

`stream-messages-runner` returns `{:promise :abort!}`. The runner's
`message` frames are decoded host-side by `process-sdk-message` into
provider callbacks (`:on-text`, `:on-thinking`, `:on-tool-start`,
`:on-tool-args`, `:on-tool-result`, `:on-error`), which the agent layer
turns into `:agent/*` events:

| Message type | What Xi does |
|---|---|
| `stream_event` | SSE events: text/thinking deltas, tool_use blocks, usage |
| `assistant` | Fallback for complete assistant messages (when no stream events) |
| `user` | Extract tool_result content blocks for display |
| `system` | Capture session ID from init message |
| `result` | Record final cost/usage |
| `rate_limit_event` | Report rate limits via on-error callback |

Tool names in stream events have the MCP prefix (`mcp__xi-tools__bash`),
which `util/strip-mcp-prefix` removes for display.

SDK lifecycle quirks are contained in the runner: the query must be
`.close()`d after completion to avoid EPIPE from orphaned subprocess pipes;
abort uses `.interrupt()` then `.close()`.

The Claude CLI must never outlive the host. The SDK only kills it from a
`process.on("exit")` hook, which doesn't run when the runner dies by signal,
and `bb serve:restart` SIGKILLs just the bun host (`reap-stray-serve!`). An
orphaned CLI then finishes the in-flight turn on its own, and the restarted
server's auto-resume sends `continue` on top of a turn that actually
completed. So the runner spawns the CLI itself (`spawnClaudeCodeProcess`) and
SIGKILLs it as soon as the host is gone: stdin EOF, EPIPE on stdout, or
SIGHUP. A SIGTERM from the host (the normal kill after the terminal frame, or
after an abort) is passed on to the CLI as SIGTERM. The server also skips
auto-resume when the transcript already ends in an `end_turn`
(`session/turn-completed?`).
