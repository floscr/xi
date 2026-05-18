# MCP Tool Bridge

Xi uses the Claude Agent SDK to talk to Claude Code (CC), but **CC never executes tools directly**. Instead, Xi exposes its own tools via an in-process MCP server. CC proposes tool calls, Xi intercepts and executes them through its own pipeline — including a permission gate that can block dangerous operations.

This mirrors [Pi's claude-bridge architecture](https://github.com/nichochar/pi).

## Architecture

```
┌──────────────────────────────────────────────────────┐
│  Claude Code (subprocess via SDK)                    │
│                                                      │
│  Built-in tools: DISABLED (disallowedTools)          │
│  Available tools: mcp__xi-tools__* only              │
│                                                      │
│  CC proposes: mcp__xi-tools__bash {command: "ls"}    │
└──────────────┬───────────────────────────────────────┘
               │ MCP call
               ▼
┌──────────────────────────────────────────────────────┐
│  Xi MCP Handler (provider.cljs)                      │
│                                                      │
│  1. Receive tool call from CC                        │
│  2. Dispatch :tool-call hook → permission gate       │
│  3. If blocked (hook returns nil) → return error     │
│  4. If allowed → execute via Xi tool registry        │
│  5. Return result to CC                              │
└──────────────────────────────────────────────────────┘
```

## Key files

| File | Role |
|------|------|
| `src/xi/provider.cljs` | SDK integration, MCP server, stream processing |
| `src/xi/tools/registry.cljs` | Tool definitions and execute fns |
| `src/xi/tools/*.cljs` | Individual tools (bash, read, write, edit, grep, find, ls) |
| `src/xi/ext/permission_gate.cljs` | Permission gate extension (blocks dangerous ops) |
| `src/xi/ext/core.cljs` | Extension registry, hook dispatch |

## How it works

### 1. CC's built-in tools are disabled

```clojure
(def ^:private DISALLOWED_BUILTIN_TOOLS
  ["Read" "Write" "Edit" "Glob" "Grep" "Bash" "Agent" "AskClaude"
   "NotebookEdit" "EnterWorktree" "ExitWorktree" ...])
```

CC is started with `permissionMode: "bypassPermissions"` (so it doesn't prompt on stdin) and `disallowedTools` listing every built-in tool. The only tools CC can see are Xi's MCP tools.

### 2. Xi's tools are exposed via MCP

`build-mcp-server` in `provider.cljs` creates an in-process MCP server using `createSdkMcpServer` from the SDK. Each Xi tool (from `tools/registry.cljs`) becomes an MCP tool:

- Tool name: `bash`, `read`, `write`, etc. (CC sees them as `mcp__xi-tools__bash`)
- Input schema: converted from JSON Schema to Zod (required by the SDK)
- Handler: executes through Xi's pipeline

### 3. Permission gate hooks

Every MCP tool handler dispatches the `:tool-call` hook before execution:

```clojure
(let [gated (ext/dispatch-hook-transform :tool-call tool-call {:cwd cwd})]
  (if (nil? gated)
    ;; Blocked — return error to CC
    ...
    ;; Allowed — execute
    ...))
```

The `dispatch-hook-transform` function chains all registered `:tool-call` hooks. If any hook returns `nil`, the chain short-circuits and the tool is blocked. The MCP handler returns an error result to CC, which sees "Blocked by Xi permission gate".

### 4. JSON Schema → Zod conversion

The SDK's `createSdkMcpServer` requires Zod schemas. `json-schema-prop->zod` and `json-schema->zod-shape` in `provider.cljs` convert Xi's JSON Schema tool definitions to Zod types at MCP server creation time.

## Permission gate

The permission gate (`ext/permission_gate.cljs`) blocks:

- **Writes to sensitive paths**: `/Mail/`, `/.ssh/`, `/.gnupg/`, `/.password-store/`
- **Writes to protected paths**: `.env`, `.git/`, `node_modules/`
- **Dangerous bash patterns**: `rm -rf`, `sudo`, `chmod -R`, `dd if=`, etc.

To add a blocked pattern, add it to `GUARDED_PATTERNS` (for bash commands) or `BLOCKED_PATHS` / `BLOCKED_WRITE_PATHS` (for file operations).

## NixOS executable resolution

The SDK ships a native CC binary that can't run on NixOS (wrong `ld-linux`). `resolve-claude-executable` follows the `which claude` symlink to find the `.js` entrypoint and passes it as `pathToClaudeCodeExecutable`. The SDK detects the `.js` extension and runs it via bun/node.

## Session management

The provider tracks the current Claude session ID via an in-memory atom (`session-state`). Key functions:

- **`get-session-id`** — returns the current Claude CLI session ID
- **`clear-session!`** — resets session state, forcing the next `query()` to start a new session

These are used by the [session tree](session-tree.md) during navigation: clearing the session ensures the next turn starts a fresh Claude conversation rather than resuming the old one.

The `resume-session-id` option in `query()` lets Xi continue an existing Claude session across turns. When set to `nil` (after tree navigation), the SDK creates a new session with a new JSONL file.

## Stream processing

The SDK's `query()` returns an async generator yielding messages:

| Message type | What Xi does |
|---|---|
| `stream_event` | Process SSE events (text deltas, tool_use blocks, usage) |
| `assistant` | Fallback for complete assistant messages (when no stream events) |
| `user` | Extract tool_result content blocks for display |
| `system` | Capture session ID from init message |
| `result` | Record final cost/usage |
| `rate_limit_event` | Report rate limits via on-error callback |

Tool names in stream events have the MCP prefix (`mcp__xi-tools__bash`), which `strip-mcp-prefix` removes for display.
