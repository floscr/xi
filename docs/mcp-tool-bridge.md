# MCP Tool Bridge (the Claude provider)

Xi uses the Claude Agent SDK to talk to Claude Code (CC), but **CC never
executes tools directly**. The Claude provider is a program like every
other provider (the `anthropic` package of
[xi-providers](https://github.com/floscr/xi-providers), spawned per turn,
see the [provider protocol](provider-protocol.md)). It runs the SDK,
exposes Xi's tools to CC through an in-process MCP server and **proxies
every tool call back to Xi** as a `tool-call` frame. Xi executes the call
through its own pipeline — including the rules policy step that can block
dangerous operations — so the tool registry and the rules engine never leave
the Xi process.

## Architecture

```
┌──────────────────────────────────────────────────────┐
│  Claude Code (subprocess of the provider, via SDK)   │
│                                                      │
│  Built-in tools: DISABLED (tools: [] whitelist)      │
│  Available tools: mcp__xi-tools__* only              │
│                                                      │
│  CC proposes: mcp__xi-tools__bash {command: "ls"}    │
└──────────────┬───────────────────────────────────────┘
               │ MCP call (in-process in the provider)
               ▼
┌──────────────────────────────────────────────────────┐
│  Claude provider (xi-providers/packages/anthropic)   │
│                                                      │
│  · builds the SDK MCP server from the turn's         │
│    tools (JSON Schema → Zod)                         │
│  · decodes SDK messages into text / thinking /       │
│    tool-start / tool-args / tool-done frames         │
│  · proxies each tool call as a `tool-call` frame     │
│    and waits for the `tool-result` frame             │
└──────────────┬───────────────────────────────────────┘
               │ newline-delimited JSON over stdio
               ▼
┌──────────────────────────────────────────────────────┐
│  Xi (xi.providers.process)                           │
│                                                      │
│  1. Receive a tool-call frame                        │
│  2. Run :tool-policy (the rules engine)              │
│  3. nil → "Blocked by Xi permission gate" error      │
│     {:intercepted true :result …} → return result    │
│     else → execute via Xi's tool registry            │
│  4. Send the tool-result frame back                  │
└──────────────────────────────────────────────────────┘
```

The frames are the [provider protocol](provider-protocol.md)'s; nothing in
them is Claude-specific. One provider process per turn, so turns of
different chats never interleave.

## Key files

| File | Role |
|------|------|
| `xi-providers/packages/anthropic/src/turn.ts` | query options, the MCP server, the login freshness gate |
| `xi-providers/packages/anthropic/src/decode.ts` | SDK messages → protocol frames, errors shown once per turn |
| `xi-providers/packages/anthropic/src/cli.ts` | which Claude CLI runs, and keeping it from outliving the turn |
| `xi-providers/packages/anthropic/package.json` | the SDK version, and the manifest |
| `xi-providers/packages/anthropic/nix/` | pins the Claude CLI release; built to the `claude` out-link — see [The pinned CLI](#the-pinned-cli) |
| `src/xi/providers/process.cljs` | Xi's side: the turn request, frames → callbacks, tool calls through policy + registry |
| `src/xi/providers/runner.cljs` | spawn and framing |
| `src/xi/tools/registry.cljs` | Tool definitions and execute fns |
| `src/xi/tools/*.cljs` | Individual tools (bash, read, write, edit, grep, find, ls) |
| `src/xi/ext/rules.cljs` | `tool-policy`: the rules engine's decision on each tool call |

## How it works

### 1. CC's built-in tools are disabled

CC is started with `permissionMode: "bypassPermissions"` (so it doesn't
prompt on stdin) and a **whitelist**: `tools: []` disables every built-in,
`allowedTools: ["mcp__xi-tools__*"]` exposes only Xi's MCP tools
(`queryOptions` in the provider's `turn.ts`).

### 2. Xi's tools are exposed via MCP — in the provider

Xi resolves the turn's tool surface with `resolve-tooling`
(extension extras/removals, an agent profile's `:only-tools` allowlist) and
sends the ordered definitions as the turn request's `tools`. The provider
builds an in-process MCP server from them (`createSdkMcpServer`), converting
each JSON Schema to Zod. Each tool's handler doesn't execute anything — it
sends a `tool-call` frame and resolves when the matching `tool-result`
arrives.

Extensions extend the tool surface per assembly via
`:extra-tool-definitions` / `:extra-tool-registry` (e.g. `kb_*`,
`web_search` — see [extensions.md](extensions.md)). The tooling seam is
fn-valued and deref'd fresh each turn, so `/ext enable|disable` takes
effect on the next turn.

On an agent run (`--agent`), the definitions are filtered to the profile's
`:tools` (`:only-tools` on the room; see the guide's [agent profiles](guide/agents.md)),
so an unlisted tool is never advertised.

### 3. The tool policy (host-side)

Every tool call passes one policy step before it runs: the rules engine
(`xi.ext.rules/tool-policy`), wired by `xi.cli` and handed to every turn as
`:tool-policy`. It is core, not an extension surface — extensions
can't add to it or skip it. `(fn [tool-call ctx]) → promise` of:

| Return | Meaning |
|---|---|
| tool-call | run it |
| `nil` | block — CC sees "Blocked by Xi permission gate" (an ask answered no) |
| `{:intercepted true :result …}` | return this result instead (a rule's deny or nudge) |

If deciding throws, the call is denied. The ctx provides `{:dispatch!
:get-state :room-id :cwd :confirm!}` — `confirm!` raises a dialog in the
connected clients (TUI/web) and resolves with the answer, which is how rule
asks and `/commit` confirm work. Tool exec-fns get the same ctx.

## Permission gate & rules engine

Tool-call policy (allow / deny / nudge / ask / confirm) is driven by the
declarative **rules engine** (`xi.ext.rules` + `xi.rules.store` /
`xi.rules`) — see the guide's [rules reference](guide/rules-reference.md) for the full reference. In precedence
order: an immutable hard-block (agents can never write the global rules
file; a repo's `.xi/rules.edn` asks every time), a
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
  `server-control` default rule; the executors (bash, run_task, clj `sh`)
  run an approved one detached via `xi.server-control`.

To add a policy, add a rule (`.xi/rules.edn`, `/rules`, or a default in
`xi.rules.defaults`).

## Claude CLI resolution (NixOS)

The SDK ships a native, generically-linked CC binary that can't run on NixOS
(wrong `ld-linux`). The provider's `resolveClaudeExecutable()` (`src/cli.ts`) instead
resolves, in order: `XI_CLAUDE_CLI_PATH`, the pinned `claude/bin/claude`
out-link, then `claude` from `PATH` (following the symlink with
`realpathSync`), and passes the result as `pathToClaudeCodeExecutable`.

### The pinned CLI

The CLI gates new model ids on its own version ("Claude Code X does not
support this model; version Y or newer is required") and nixpkgs lags
upstream, so the provider pins the release (paths in xi-providers):

| File | Role |
|------|------|
| `packages/anthropic/nix/claude-code-manifest.json` | the upstream release manifest — this *is* the pin |
| `packages/anthropic/nix/flake.nix` + `flake.lock` | nixpkgs' `claude-code` built with that manifest |
| `packages/anthropic/claude` | out-link to the build (gitignored) |

Nothing is built by hand and there is no dev shell to enter: before each turn
the provider compares the manifest's version with the version the out-link
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
`bb claude:build` forces a build (both run the provider's `src/pin.ts` in
`providers/`; commit the bump in xi-providers). Both take effect on the next
turn. A package install has no `nix/`: it runs the `claude` on `PATH`, else
the SDK's own binary.

## Prompt caching & token hygiene

Prompt caching is done by the Claude CLI (it places the `cache_control`
breakpoints, 1h TTL on a subscription). Xi's job is to keep the request prefix
— tools → system prompt → messages — byte-stable between turns and free of
dead weight. The rules:

- **The system prompt is built once per room**, at creation or cwd change
  (`system-prompt/load-agents-parts` + extension prompts), not per turn. An
  AGENTS.md edit mid-session therefore does not invalidate the cache; it is
  picked up by the next room / server restart.
- **Xi is the sole loader of project instructions.** `queryOptions` passes
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

The provider decodes each SDK message into protocol frames (`decode.ts`);
`xi.providers.process` maps those onto the turn's callbacks (`:on-text`,
`:on-thinking`, `:on-tool-start`, `:on-tool-args`, `:on-tool-result`,
`:on-error`), which the agent layer turns into `:agent/*` events:

| Message type | What the provider sends |
|---|---|
| `stream_event` | SSE events: text/thinking deltas, tool_use blocks, usage |
| `assistant` | Fallback for complete assistant messages (when no stream events) |
| `user` | Extract tool_result content blocks for display |
| `system` | Capture session ID from init message |
| `result` | Record final cost/usage |
| `rate_limit_event` | Report rate limits via on-error callback |

Tool names in stream events have the MCP prefix (`mcp__xi-tools__bash`),
which the provider strips for display.

SDK lifecycle quirks are contained in the provider: the query must be
`.close()`d after completion to avoid EPIPE from orphaned subprocess pipes;
abort uses `.interrupt()` then `.close()`.

The Claude CLI must never outlive the host. The SDK only kills it from a
`process.on("exit")` hook, which doesn't run when the provider dies by signal,
and `bb serve:restart` SIGKILLs just the bun host (`reap-stray-serve!`). An
orphaned CLI then finishes the in-flight turn on its own, and the restarted
server's auto-resume sends `continue` on top of a turn that actually
completed. So the provider spawns the CLI itself (`spawnClaudeCodeProcess`) and
SIGKILLs it as soon as Xi is gone (the kit's `shutdown` hook): stdin EOF, EPIPE on stdout, or
SIGHUP. After `done`, or on a SIGTERM from Xi (after an abort), the CLI gets
SIGTERM and three seconds before SIGKILL; the provider exits once it is gone,
which Xi takes as "the transcript is settled". The server also skips
auto-resume when the transcript already ends in an `end_turn`
(`session/turn-completed?`).
