# Plan: Xi — Personal Coding Agent in ClojureScript + Bun

## Context
Build a Pi-compatible coding agent in pure ClojureScript, compiled via shadow-cljs (`:node-script` target), run on Bun. Zero npm runtime deps. Sessions are interchangeable with Pi. Extensions are baked-in but use Pi's lifecycle hook architecture so Pi extensions can be ported.

**Core architectural decisions:**
- **shadow-cljs over Squint** — persistent data structures for immutable session trees (rewind = pointer swap, branching = sibling chain)
- **Build-time JVM dependency is acceptable** — shadow-cljs needs Java + Node to compile, output is pure JS for Bun
- **No npm runtime deps** — Bun.serve, Bun.spawn, node:crypto, fetch+ReadableStream, node:fs
- **Provider = raw API calls with Pi's OAuth tokens** — read tokens from `~/.pi/agent/auth.json`, call Anthropic Messages API directly via fetch+SSE. No Claude Agent SDK dependency.
- **Pi-compatible sessions** — store in `~/.pi/agent/sessions/{cwd-encoded}/`, same JSONL format (version 3)
- **Baked-in extensions** — no dynamic loading, but same lifecycle hook API as Pi for portability

## Phase 0: Foundation & Validation
- [x] **Spike shadow-cljs + Bun** — minimal `shadow-cljs.edn` with `:target :node-script`, compile, run with `bun target/main.js`. Confirm `Bun.serve`, `Bun.spawn`, `node:crypto`, `fetch` work from CLJS via js interop.
- [x] **Validate raw API auth** — read `~/.pi/agent/auth.json`, call `https://api.anthropic.com/v1/messages` with `x-api-key: <access_token>` + `anthropic-beta: oauth-2025-04-20,claude-code-20250219`. Confirm streaming works. Also test `ANTHROPIC_API_KEY` env var fallback.
- [x] **Project scaffolding** — `shadow-cljs.edn`, `package.json` (shadow-cljs devDep only), `deps.edn`, directory structure, `.gitignore`, `AGENTS.md`.

## Phase 1: Core Agent Loop (Minimal Viable Agent)
- [x] **`src/xi/provider.cljs`** — Anthropic Messages API SSE client. `fetch` with `ReadableStream` for incremental event parsing (`event: content_block_delta`, `event: content_block_stop`, `event: message_delta`, `event: message_stop`). Auth: read `~/.pi/agent/auth.json` for OAuth token, fall back to `ANTHROPIC_API_KEY` env var. Token refresh on 401 via refresh_token grant. Tool call argument accumulation across deltas.
- [x] **`src/xi/loop.cljs`** — Agent loop: send messages → stream response → if tool_use stop reason, execute tools → append tool results → recur. Promise-based async. Emit events: `:text-delta`, `:tool-start`, `:tool-end`, `:turn-complete`, `:error`.
- [x] **Basic tools: `read`, `bash`, `ls`** — minimum to be useful. `read` via `Bun.file().text()`, `bash` via `Bun.spawn` with stdout/stderr capture + timeout, `ls` via `node:fs/promises.readdir`.
- [x] **`src/xi/cli.cljs`** — Entry point. Parse args, load config, start interactive loop: read user input from stdin → run agent turn → print streamed output. Simple `process.stdin` readline, no TUI.
- [x] **System prompt** — Load AGENTS.md from project root + parents (same walk-up as Pi). Hardcoded base system prompt with tool descriptions. Cache-control-friendly structure (stable prefix, then dynamic context appended).

## Phase 2: Remaining Core Tools
- [x] **`write` tool** — `Bun.write`, auto-create parent dirs via `mkdirSync({recursive: true})`.
- [x] **`edit` tool** — exact text replacement (oldText → newText). Match semantics of Pi/Claude Code edit.
- [x] **`grep` tool** — `Bun.spawn` ripgrep (`rg`) subprocess. Respect `.gitignore`.
- [x] **`find` tool** — `Bun.spawn` `fd` subprocess, fall back to `find`.

## Phase 3: Session Persistence (Pi-Compatible)
- [x] **`src/xi/session.cljs`** — JSONL session store using Pi's exact format (v3). Write JSON objects matching Pi's schema. Support all 9 line types (see "Pi Session Format Spec" in Notes).
- [x] **Session directory** — match Pi convention: `~/.pi/agent/sessions/{cwd-encoded}/` where cwd-encoded = path with `/` → `-`, wrapped in `--`. File names: `{ISO-timestamp}_{UUIDv7}.jsonl`.
- [x] **Message→API conversion** — translate Pi session messages to Anthropic API format for sending (same logic as `convert.js`): `toolCall` → `tool_use`, `toolResult` → `tool_result` wrapped in user message, thinking blocks with signature pass-through.
- [x] **Session tree reconstruction** — on startup, reconstruct message chain from head back to root by following `parentId` pointers.
- [x] **Session listing & resume** — list sessions from the Pi session directory, show names + timestamps, resume selected session. Both Xi and Pi sessions appear together.

## Phase 4: Extension System (Pi-Compatible Lifecycle Hooks)
- [x] **`src/xi/ext/core.cljs`** — Extension registry and lifecycle dispatcher. Extensions register via a map of `{:name :hooks :tools :commands :shortcuts}`. Hooks are multimethods or callback vectors dispatched at lifecycle points.
- [x] **Lifecycle events** — implement the core events that Pi extensions rely on:
  - `session-start` — session loaded/created
  - `session-shutdown` — session ending
  - `turn-start` — user message received, before agent loop
  - `turn-end` — agent turn completed
  - `before-agent-start` — before sending to API (can modify messages/system prompt)
  - `agent-end` — after API response fully consumed
  - `context` — filter/transform messages before sending to API
  - `tool-call` — intercept tool call (block/allow/modify)
  - `tool-result` — intercept tool result
  - `input` — transform user input
  - `session-before-compact` — before compaction
- [x] **Tool registration** — extensions can register custom tools with name, description, schema, execute fn. Tools appear in system prompt and tool list sent to API.
- [x] **Command registration** — extensions can register `/commands` with name, description, handler fn. Dispatched when user types `/command`.
- [x] **Extension context** — each extension receives a context map with: `send-user-message`, `append-entry`, `get-session-name`, `set-session-name`, `get-flag`, `exec` (subprocess), and UI hooks (initially just print-based).

## Phase 5: Port Core Pi Extensions (Baked-In)
- [x] **plan-mode** — `/plan` toggle. Injects plan-mode system prompt ("read-only exploration mode"). Writes plans to `tasks/todo.md`. Guards bash in plan mode (read-only). Tracks `- [ ]` / `- [x]` progress.
- [x] **permission-gate** — dangerous bash confirmation (rm -rf, sudo, etc.). Block writes to `.env`/`.git`/`node_modules`. Hard-block private paths (`/Mail/`, etc.).
- [x] **kb** — knowledge base integration. Register `kb_search`, `kb_get`, `kb_store` tools that shell out to the `kb` CLI binary. `/kb learn` command for session learning extraction.
- [x] **commit** — git commit workflow. `git_overview`, `git_file_diff`, `git_hunk`, `git_stage_hunks`, `git_commit_with_user_approval` tools for hunk-level staging.
- [x] **web** — `fetch` tool. HTML→markdown conversion, JSON/RSS handling. Jina Reader fallback for JS-heavy pages.
- [x] **sub-project** — read Babashka profile EDN to find sub-projects for current CWD, inject focus context.
- [x] **parmezan** — post-edit hook for Clojure files, run `parmezan` CLI to fix unbalanced delimiters.
- [x] **done-notify** — desktop notification via `dunstify` on agent turn completion.
- [x] **terminal-title** — auto-name sessions from first message, set terminal title via ANSI escape.

## Phase 6: Context Compaction
- [x] **`src/xi/compaction.cljs`** — token estimation (chars/4 heuristic). When messages exceed ~80% of context window, summarize older messages.
- [x] **compact-bridge approach** — use `claude -p --bare --system-prompt` subprocess for summarization (same approach as Pi's compact-bridge). Handles split-turn summaries.
- [x] **Preserve metadata** — file operations (which files read/written/edited) must be preserved verbatim in summaries. Session tree gets a compacted node.

## Phase 7: Token Refresh & OAuth Fallback
- [x] **Auto-refresh** — when API returns 401, read refresh_token from `~/.pi/agent/auth.json`, POST to `https://console.anthropic.com/v1/oauth/token` with `grant_type=refresh_token`. Update `auth.json` on success.
- [x] **PKCE flow (fallback)** — ~~N/A: superseded by claude CLI bridge approach.~~ The CLI handles its own auth (OAuth, API keys, keychain) internally. Xi no longer makes direct API calls, so PKCE is not needed.
- [x] **API key primary** — `ANTHROPIC_API_KEY` env var always works without OAuth. OAuth is the convenience path.

## Phase 8: TUI
- [x] **`src/xi/tui/render.cljs`** — differential terminal renderer. Track previous frame cells, diff, emit only changed ANSI sequences.
- [x] **`src/xi/tui/editor.cljs`** — multi-line input editor. Raw mode via `process.stdin.setRawMode(true)`. Arrow keys, backspace, enter, ctrl-c/d, history.
- [x] **`src/xi/tui/markdown.cljs`** — render markdown in output. Code blocks, bold, lists. Minimal parser.
- [x] **Streaming display** — show text token-by-token as it arrives, tool calls as they execute, spinner/status line.

## Phase 9: Polish
- [x] **Config** — `~/.pi/agent/settings.json` reading (model, provider). `~/.pi/agent/models.json` for custom model definitions.
- [x] **Error handling** — graceful on network failures, token expiry, tool crashes, malformed SSE.
- [x] **`/commands`** — `/tree` (visualize branches), `/rewind` (navigate to node), `/compact` (force compaction), `/help`, `/clear`, `/model`.
- [x] **Rewind / branching** — navigate to any node in session tree; next message branches from there. `/tree` shows branch visualization.

## Notes

### Project structure
```
xi/
├── shadow-cljs.edn
├── package.json              # shadow-cljs devDep only
├── deps.edn                  # CLJS deps
├── AGENTS.md
├── tasks/
│   └── todo.md               # this file
├── resources/
│   └── system-prompt.txt     # base system prompt
├── src/xi/
│   ├── cli.cljs              # entry point
│   ├── provider.cljs         # Anthropic SSE client + auth
│   ├── loop.cljs             # agent loop
│   ├── session.cljs          # Pi-compatible JSONL persistence
│   ├── compaction.cljs       # context summarization
│   ├── ext/
│   │   ├── core.cljs         # extension registry + lifecycle dispatch
│   │   ├── plan_mode.cljs
│   │   ├── permission_gate.cljs
│   │   ├── kb.cljs
│   │   ├── commit.cljs
│   │   ├── web.cljs
│   │   ├── sub_project.cljs
│   │   ├── parmezan.cljs
│   │   ├── done_notify.cljs
│   │   └── terminal_title.cljs
│   ├── tools/
│   │   ├── read.cljs
│   │   ├── write.cljs
│   │   ├── edit.cljs
│   │   ├── bash.cljs
│   │   ├── grep.cljs
│   │   └── ls.cljs
│   └── tui/
│       ├── render.cljs       # differential renderer
│       ├── editor.cljs       # multi-line input
│       └── markdown.cljs     # output formatting
└── target/                   # compiled JS output
```

### Provider: Raw API calls with Pi's OAuth tokens

Xi does NOT use the `@anthropic-ai/claude-agent-sdk`. Instead it makes direct API calls:

**Auth resolution order:**
1. `ANTHROPIC_API_KEY` env var → use as `x-api-key` header (simplest)
2. `~/.pi/agent/auth.json` → read `access` token, use as `x-api-key` + beta headers
3. Full PKCE flow (Phase 7) → obtain + store tokens in `auth.json`

**API call pattern:**
```
POST https://api.anthropic.com/v1/messages
Headers:
  Authorization: Bearer <oauth_access_token>   (for OAuth tokens)
  x-api-key: <api_key>                          (for API key fallback)
  anthropic-version: 2023-06-01
  anthropic-beta: oauth-2025-04-20
  content-type: application/json

Body:
  model, max_tokens, system (with cache_control), messages, tools, stream: true
```

**Auth findings (validated 2026-05-08):**
- OAuth tokens use `Authorization: Bearer`, NOT `x-api-key` (x-api-key returns 401)
- `anthropic-beta: oauth-2025-04-20` is sufficient (claude-code-20250219 not needed)
- OAuth rate limits are tighter than API key — need retry with backoff
- Token refresh via POST to token endpoint works, returns new access_token

**SSE events to parse:**
- `message_start` — message metadata, usage
- `content_block_start` — new content block (text, tool_use, thinking)
- `content_block_delta` — incremental text/tool args/thinking
- `content_block_stop` — block complete
- `message_delta` — stop_reason, usage update
- `message_stop` — message complete
- `error` — API error

**Token refresh:**
```
POST https://console.anthropic.com/v1/oauth/token
Body: grant_type=refresh_token&refresh_token=<token>&client_id=<client_id>
```

### Message format conversion (Pi → Anthropic API)

Pi stores messages in its own format. Before sending to the API, convert:

| Pi format | Anthropic API format |
|-----------|---------------------|
| `{role: "user", content: [{type: "text", text}]}` | `{role: "user", content: [{type: "text", text}]}` (same) |
| `{role: "assistant", content: [{type: "toolCall", id, name, arguments}]}` | `{role: "assistant", content: [{type: "tool_use", id, name, input}]}` |
| `{role: "assistant", content: [{type: "thinking", thinking, thinkingSignature}]}` | `{role: "assistant", content: [{type: "thinking", thinking, signature}]}` |
| `{role: "toolResult", toolCallId, content, isError}` | `{role: "user", content: [{type: "tool_result", tool_use_id, content, is_error}]}` |

Key: `arguments` → `input`, `toolCall` → `tool_use`, `thinkingSignature` → `signature`, tool results become user messages.

### Pi Extension API — what Xi must support

**Lifecycle events (priority order):**
1. `context` — filter/transform messages before API call (critical: plan-mode, sub-project use this)
2. `tool-call` — intercept before execution (critical: permission-gate uses this)
3. `before-agent-start` — modify system prompt/tools before API call
4. `turn-start` / `turn-end` — bookkeeping, notifications
5. `session-start` / `session-shutdown` — initialization, cleanup
6. `input` — transform user input (plan-mode uses this)
7. `tool-result` / `tool-execution-end` — post-tool hooks (parmezan uses this)
8. `session-before-compact` — compaction preprocessing
9. `agent-end` — post-response (done-notify uses this)

**Extension registration shape (Xi equivalent of Pi's `ExtensionAPI`):**
```clojure
{:name "plan-mode"
 :hooks {:context       (fn [messages opts] modified-messages)
         :tool-call     (fn [tool-name args] :allow | :block | modified-args)
         :input         (fn [text] modified-text)
         :turn-start    (fn [ctx] nil)
         :agent-end     (fn [ctx] nil)}
 :tools [{:name "update_plan" :description "..." :schema {...} :execute (fn [params] result)}]
 :commands [{:name "plan" :description "Toggle plan mode" :handler (fn [args ctx] nil)}]
 :shortcuts [{:key "ctrl+p" :description "Toggle plan mode" :handler (fn [ctx] nil)}]}
```

### Pi Session Format Spec (version 3)

**Directory layout:**
```
~/.pi/agent/sessions/{cwd-encoded}/{ISO-timestamp}_{UUIDv7}.jsonl
```
Where `cwd-encoded` = path with `/` → `-`, wrapped in `--`.
E.g. `/home/floscr/Code/Projects/xi` → `--home-floscr-Code-Projects-xi--`

**Tree structure:** Every non-header line has `id` (8-char hex) and `parentId` (8-char hex or `null`). Lines form a DAG via parent pointers.

**Line types (9 total, each line = one JSON object):**

1. **Session header** (first line):
   `{"type":"session","version":3,"id":"<UUIDv7>","timestamp":"<ISO>","cwd":"/path"}`

2. **Model change:**
   `{"type":"model_change","id":"<hex8>","parentId":null,"timestamp":"<ISO>","provider":"anthropic","modelId":"claude-opus-4-6"}`

3. **Thinking level change:**
   `{"type":"thinking_level_change","id":"<hex8>","parentId":"<hex8>","timestamp":"<ISO>","thinkingLevel":"high"}`

4. **Custom events** (plan-mode, sub-project):
   `{"type":"custom","customType":"plan-mode","data":{...},"id":"<hex8>","parentId":"<hex8>","timestamp":"<ISO>"}`

5. **User message:**
   `{"type":"message","id":"<hex8>","parentId":"<hex8>","timestamp":"<ISO>","message":{"role":"user","content":[...],"timestamp":<ms>}}`

6. **Assistant message** (content = Anthropic format with Pi naming):
   `{"type":"message","id":"<hex8>","parentId":"<hex8>","timestamp":"<ISO>","message":{"role":"assistant","content":[...],"api":"anthropic-messages","provider":"...","model":"...","usage":{...},"stopReason":"...","timestamp":<ms>}}`

7. **Tool result:**
   `{"type":"message","id":"<hex8>","parentId":"<hex8>","timestamp":"<ISO>","message":{"role":"toolResult","toolCallId":"...","toolName":"...","content":[...],"isError":false,"timestamp":<ms>}}`

8. **Custom message** (injected context):
   `{"type":"custom_message","customType":"...","content":"...","display":false,"id":"<hex8>","parentId":"<hex8>","timestamp":"<ISO>"}`

9. **Session info** (naming):
   `{"type":"session_info","id":"<hex8>","parentId":"<hex8>","timestamp":"<ISO>","name":"Session Title"}`

**Key details:**
- IDs are 8-char random hex (except session header = full UUIDv7)
- `provider` field: `"anthropic"` (direct API) vs `"claude-bridge"` (OAuth). Xi should use `"xi"` or `"anthropic"`.
- Content blocks: `toolCall` (not `tool_use`), `thinkingSignature` (not `signature`) — Pi's internal naming
- Usage includes cost breakdown: `{input, output, cacheRead, cacheWrite, totalTokens, cost: {input, output, cacheRead, cacheWrite, total}}`

### Pi extensions — what each one does (for porting reference)

| Extension | Purpose | Xi phase | Hooks used |
|-----------|---------|----------|------------|
| plan-mode | `/plan` toggle, read-only guards, progress tracking | 5 | context, input, tool_call, turn_start |
| permission-gate | Dangerous bash confirmation, path blocking | 5 | tool_call |
| kb | Knowledge base tools (search/get/store) | 5 | tools only |
| commit | Hunk-level git staging + commit workflow | 5 | tools only |
| web | Fetch tool with HTML→md | 5 | tools only |
| compact-bridge | Compaction via claude subprocess | 6 | session_before_compact |
| sub-project | Babashka profile → focus context | 5 | context, before_agent_start |
| parmezan | Fix Clojure delimiters post-edit | 5 | tool_execution_end |
| done-notify | Desktop notification on completion | 5 | agent_end |
| terminal-title | Auto-name session, set terminal title | 5 | session_start, turn_end |
| review | Code review with finding tools | later | tools + commands |
| perplexity | Perplexity search tool | later | tools only |
| mcp-adapter | MCP server integration | later | before_agent_start, tools |
| teleport | SCP sessions to remote | later | commands only |
| clipboard-image | Paste images from clipboard | later | input |

### Bun provides (no npm deps needed)
- `Bun.serve` — HTTP server (OAuth callback, future MCP)
- `Bun.spawn` — subprocess for bash/grep/find tools + claude CLI
- `Bun.file` / `Bun.write` — file I/O
- `node:crypto` — SHA-256 for PKCE, randomBytes for IDs
- `fetch` + `ReadableStream` — SSE streaming from Anthropic
- `process.stdin.setRawMode` — raw terminal input

### Tool name mapping (Pi → API)
Pi uses lowercase tool names internally: `read`, `write`, `edit`, `bash`.
Claude Code SDK expects PascalCase: `Read`, `Write`, `Edit`, `Bash`.
When Xi sends tools to the API directly, use whatever names Xi defines — no SDK mapping needed.
When reading Pi sessions with `provider: "claude-bridge"`, tool names may be PascalCase in API responses.
