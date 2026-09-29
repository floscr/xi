# MCP Tool Bridge (SDK Runner)

Xi uses the Claude Agent SDK to talk to Claude Code (CC), but **CC never
executes tools directly**. The SDK runs in a separate **runner process**
(`runner/runner.mjs`, its own `node_modules`, freely upgradable SDK); the
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
│  Runner (runner/runner.mjs, spawned per turn)        │
│                                                      │
│  · builds the SDK MCP server from the host's         │
│    toolDefs (JSON Schema → Zod)                      │
│  · forwards every SDK message to the host            │
│    as a `sdk-message` frame                          │
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
| `{type:"sdk-message", message}` | one SDK message, decoded host-side by `process-sdk-message` |
| `{type:"tool-call", id, name, arguments}` | proxied tool call awaiting a `tool-result` |
| `{type:"done"}` | terminal: turn complete |
| `{type:"error", message}` | terminal: turn failed |

The runner guarantees a **single terminal frame** (`done` or `error`); the
host ignores any frame after it.

## Key files

| File | Role |
|------|------|
| `runner/runner.mjs` | SDK integration: query lifecycle, MCP server, tool-call proxying |
| `runner/package.json` | pins the SDK version — upgrade here, `npm install` in `runner/` |
| `src/xi/providers/anthropic.cljs` | spawns the runner, decodes SDK messages, services tool calls |
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

What still lives in `ext/permission_gate.cljs`:

- **`GUARDED_PATTERNS`** — dangerous bash substrings (`rm -rf`,
  `fs/delete-dir` / `fs/delete-tree`, `chmod -R`, `dd if=`, …) that the `clj`
  gate confirms before running scanned commands.
- **Server control** (`server-restart` / `server-stop`) — handled specially
  (`ask-server-control`, detached run) and deliberately never expressed as a
  rule.

To add a policy, prefer a rule (`.xi/rules.edn` or `/rules`); only the guarded
patterns / server-control live in `ext/permission_gate.cljs`.

## Claude CLI resolution (NixOS)

The SDK ships a native, generically-linked CC binary that can't run on NixOS
(wrong `ld-linux`). The runner's `resolveClaudeExecutable()` instead resolves
`claude` from `PATH` (following the symlink with `realpathSync`) and passes
it as `pathToClaudeCodeExecutable`; `XI_CLAUDE_CLI_PATH` overrides it
explicitly.

## Session resume

There is no provider-side session atom. The provider session id lives in
app state at `[:rooms room-id :session :provider-session-id]` — set by
`:agent/turn-end`, passed to the next turn as `:resume-session-id` (SDK
`resume` option). Clearing it (e.g. `/clear`, `/tree` navigation) makes the
next turn start a fresh Claude session with a new JSONL file.

## Stream processing

`stream-messages-runner` returns `{:promise :abort!}`. The runner's
`sdk-message` frames are decoded host-side by `process-sdk-message` into
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
abort uses `.interrupt()` then `.close()`; stdout write errors after the
terminal frame are swallowed to stderr.
