# MCP Tool Bridge

Xi uses the Claude Agent SDK to talk to Claude Code (CC), but **CC never executes tools directly**. Instead, Xi exposes its own tools via an in-process MCP server. CC proposes tool calls, Xi intercepts and executes them through its own pipeline — including a tool gate that can block or rewrite dangerous operations.

This mirrors [Pi's claude-bridge architecture](https://github.com/nichochar/pi).

## Architecture

```
┌──────────────────────────────────────────────────────┐
│  Claude Code (subprocess via SDK)                    │
│                                                      │
│  Built-in tools: DISABLED (tools: [] whitelist)      │
│  Available tools: mcp__xi-tools__* only              │
│                                                      │
│  CC proposes: mcp__xi-tools__bash {command: "ls"}    │
└──────────────┬───────────────────────────────────────┘
               │ MCP call
               ▼
┌──────────────────────────────────────────────────────┐
│  Xi MCP Handler (provider/claude.cljs)               │
│                                                      │
│  1. Receive tool call from CC                        │
│  2. Run :tool-gate chain (extension-composed)        │
│  3. nil → "Blocked by Xi permission gate" error      │
│     {:intercepted true :result …} → return result    │
│     tool-call → execute via Xi tool registry         │
│  4. Return result to CC                              │
└──────────────────────────────────────────────────────┘
```

## Key files

| File | Role |
|------|------|
| `src/xi/provider/claude.cljs` | SDK integration, MCP server, stream processing |
| `src/xi/tools/registry.cljs` | Tool definitions and execute fns |
| `src/xi/tools/*.cljs` | Individual tools (bash, read, write, edit, grep, find, ls) |
| `src/xi/ext/core.cljs` | Tool-gate chain composition (`compose-tool-gate`) |
| `src/xi/ext/permission_gate.cljs` | Permission gate extension (blocks dangerous ops) |

## How it works

### 1. CC's built-in tools are disabled

CC is started with `permissionMode: "bypassPermissions"` (so it doesn't
prompt on stdin) and a **whitelist**: `tools: []` disables every built-in,
`allowedTools: ["mcp__xi-tools__*"]` exposes only Xi's MCP tools.

### 2. Xi's tools are exposed via MCP

`build-mcp-server` creates an in-process MCP server using
`createSdkMcpServer` from the SDK. Each Xi tool (from `tools/registry.cljs`)
becomes an MCP tool:

- Tool name: `bash`, `read`, `write`, etc. (CC sees them as `mcp__xi-tools__bash`)
- Input schema: converted from JSON Schema to Zod (required by the SDK)
- Handler: runs the gate, then executes via the registry's exec fn, which
  receives only `{:cwd}` — tools have no app-state concerns

Extensions extend the tool surface per assembly via
`:extra-tool-definitions` / `:extra-tool-registry` (e.g. `kb_*`,
`web_search` — see [extensions.md](extensions.md)).

In personal-agent mode (`:personal-agent?`), the definitions are filtered
to `PERSONAL_AGENT_TOOLS` (`web_search` only).

### 3. The tool gate

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

### 4. JSON Schema → Zod conversion

The SDK's `createSdkMcpServer` requires Zod schemas.
`json-schema-prop->zod` / `json-schema->zod-shape` convert Xi's JSON Schema
tool definitions to Zod types at MCP server creation time.

## Permission gate

The permission gate (`ext/permission_gate.cljs`) blocks:

- **Writes to sensitive paths**: `/Mail/`, `/.ssh/`, `/.gnupg/`, `/.password-store/`
- **Writes to protected paths**: `.env`, `.git/`, `node_modules/`
- **Dangerous bash patterns**: `rm -rf`, `sudo`, `chmod -R`, `dd if=`, etc.

To add a blocked pattern, edit the pattern/path lists in
`ext/permission_gate.cljs`.

## NixOS executable resolution

The SDK ships a native CC binary that can't run on NixOS (wrong
`ld-linux`). `resolve-claude-executable` follows the `which claude` symlink
to find the `.js` entrypoint and passes it as
`pathToClaudeCodeExecutable`. The SDK detects the `.js` extension and runs
it via bun/node.

## Session resume

There is no provider-side session atom. The provider session id lives in
app state at `[:rooms room-id :session :provider-session-id]` — set by
`:agent/turn-end`, passed to the next turn as `:resume-session-id` (SDK
`resume` option). Clearing it (e.g. `/clear`, `/tree` navigation) makes the
next `query()` start a fresh Claude session with a new JSONL file.

## Stream processing

`stream-messages` returns `{:promise :abort!}`. The SDK's `query()` yields
messages processed into provider callbacks (`:on-text`, `:on-thinking`,
`:on-tool-start`, `:on-tool-args`, `:on-tool-result`, `:on-error`), which
the agent layer turns into `:agent/*` events:

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

Lifecycle quirks (the reason for the SDK version pin — see AGENTS.md):
the query must be `.close()`d after completion to avoid EPIPE from orphaned
subprocess pipes; abort uses `.interrupt()` then `.close()`.
