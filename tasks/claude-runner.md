# Claude SDK runner — extract the Anthropic dependency out of Xi

## Motivation

- The subscription Claude path (`xi.provider.claude`) runs
  `@anthropic-ai/claude-agent-sdk` **in-process**, pinned to `0.2.110`.
  Newer SDKs exit 127 (CLI resolution breaks), so Xi can't upgrade — which
  blocks running the **newer Claude CLI** and therefore the newer **fable**
  models on the Anthropic subscription.
- Goal: move the SDK + Claude CLI into a **separate runner process** with its
  own `package.json` pinning whatever (newer) SDK version works. Xi talks to it
  over a small transport-agnostic protocol. Tool calls proxy **back to the
  host** (option A) so tool execution + the rules/permission gate stay on the
  host and the runner never touches user data.
- Net effect: `@anthropic-ai/claude-agent-sdk` leaves Xi's deps; the SDK is
  unpinned in the runner; newer fable-on-subscription works.

Isolation (VM/container) is deferred — the process split alone delivers both
goals. Once the runner is a separate process on a socket, containerizing it is
just a deployment wrapper with no protocol change (Phase 2).

Zen is out of scope for this work.

## Current shape (host, in-process)

`xi.provider.claude/stream-messages [opts] → {:promise :abort!}`:
1. `build-mcp-server` — wraps Xi's tool defs as an in-process
   `createSdkMcpServer`; each handler runs `tool-gate` then `tools/run-tool`.
2. `sdk/query {:prompt :options}` — spawns the Claude CLI subprocess.
3. Consumes the async iterator, running `process-stream-event` /
   `process-assistant-message` / `extract-tool-results-from-user-msg`, which
   fire callbacks (`:on-text :on-thinking :on-tool-start :on-tool-args
   :on-tool-result :on-session :on-error`).
4. `.interrupt()` + `.close()` on abort.

Everything downstream (`agent.cljs`, callbacks) is provider-agnostic and stays
unchanged.

## Design

### Transport-agnostic message protocol

Point-to-point, bidirectional, multiplexed. Start with **stdio, newline-
delimited JSON** (runner is a child process Xi spawns; LSP-style framing; no
ports/auth). Swap to a socket later for containers — protocol identical.

Host → runner:
- `start-turn` — `{prompt model system resume-id images effort env …}`
- `tool-result` — `{id content is-error}` (reply to a proxied tool call)
- `abort`

Runner → host:
- `sdk-message` — a raw SDK message object (`stream_event` / `assistant` /
  `user` / `result` / `system` / `rate_limit_event`), forwarded verbatim
- `tool-call` — `{id name arguments}` (the runner's MCP handler asking the
  host to execute a tool)
- `done` — turn finished (carries final state: usage, cost, session-id,
  stop-reason, aborted/error flags)

Key insight: the runner is **thin**. It forwards raw SDK messages; the host
keeps `process-stream-event` / `process-assistant-message` /
`extract-tool-results-from-user-msg` **unchanged**, just fed from channel
messages instead of `sdk-query.next()`. The only new logic in the runner is the
MCP tool handler forwarding `tool-call` to the host and awaiting `tool-result`.

### Refactor `xi.provider.claude` (host)

- Extract the message-consumption loop into a decoder that takes a *source of
  SDK messages* (fn returning the next message / a callback stream) rather than
  hard-coding `sdk-query.next()`. Both the (legacy) in-process path and the
  runner path feed the same decoder.
- Extract the MCP tool-handler body (`tool-gate` → intercept/block/run-tool →
  `{:content :isError}`) into a host-side fn reused by the `tool-call` handler.
- New `start-turn!` implementation: spawn/reuse the runner child process, send
  `start-turn`, pump `sdk-message` → decoder, service `tool-call` via the
  extracted handler, resolve `:promise` on `done`; `:abort!` sends `abort`.
- Provider key stays `:claude` (repurposed to the runner-backed impl). During
  migration, keep the in-process path behind an env flag
  (`XI_CLAUDE_INPROCESS=1`) for A/B, then delete it.

### The runner (`runner/` — separate npm project)

Small bun/node program:
- Own `package.json` pinning the **new** `@anthropic-ai/claude-agent-sdk`;
  resolves the **new** `claude` CLI on its PATH.
- Reads `start-turn` from stdin; builds an SDK MCP server whose every tool
  handler forwards `tool-call` to stdout and awaits the matching `tool-result`.
- Runs `sdk/query`, forwards each SDK message as `sdk-message`, keeps the
  existing SDK lore (`.close()` after completion; `.interrupt()`+`.close()` on
  abort; EPIPE avoidance).
- Auth: as a host child it inherits `HOME`/env → `~/.pi/agent/auth.json`,
  `~/.claude/.credentials.json`, `ANTHROPIC_API_KEY` all resolve as today.

### Build / deploy

- `bb` task to build/install the runner (its own `npm install`); Xi's
  `package.json` drops `@anthropic-ai/claude-agent-sdk`.
- Xi locates the runner via a configured path (default: bundled `runner/`).
- Nix: runner becomes its own derivation; wire into the deploy.

## Slices (each independently shippable/testable)

1. **Protocol + decoder refactor (host)** — ✅ DONE. Factored `process-sdk-message`
   (per-message decoder) and `run-gated-tool` (tool-gate→registry) out of
   `stream-messages`; the in-process loop calls them. No behavior change,
   compiles clean. Decoder fixtures pending.
2. **Runner process + stdio transport** — ✅ DONE. Built `runner/`
   (`package.json` pinning the SDK + `runner.mjs`) and the host-side
   `stream-messages-runner` in what is now `xi.providers.anthropic`. Runner
   path overridable via `XI_CLAUDE_RUNNER_PATH` (defaults to the bundled
   `runner/runner.mjs`); Claude CLI resolved from PATH (NixOS-friendly),
   overridable via `XI_CLAUDE_CLI_PATH`. Host reuses `resolve-tooling` (ships
   `:defs` over the wire) + `tool-dispatcher` (services proxied `tool-call`
   frames through the gate + registry) + `process-sdk-message` (forwarded SDK
   messages). Chose **per-turn spawn** (one runner process per turn) over
   long-lived to avoid stdout frame interleaving across concurrent rooms.
3. **Unpin + fable** — ✅ DONE. Runner SDK at `^0.2.141`; Claude 5.1 / fable
   models verified live on the subscription. `@anthropic-ai/claude-agent-sdk`
   removed from Xi's `package.json`; the in-process SDK path (Zod converter,
   `build-mcp-server`, in-process `stream-messages`) and the
   `XI_CLAUDE_RUNNER` flag are deleted — the runner is the only path.

   Follow-up (same cutover): providers unified into an extension-like system —
   `xi.provider.*` → `xi.providers.*`, `claude` → `anthropic` (provider id
   `:claude` → `:anthropic`), each provider self-contained
   `{:id :start-turn! :list-models!}`, declared in `config.cljc` like
   extensions; generic model fetch in `fx.cljs`. Commits `0ef5bbc`,
   `ff1fa3e`, `983b694`.

   Layout since then (the paths above are historical): the runner and the
   pinned Claude CLI live in `providers/anthropic/` (was `runner/` + `nix/`),
   the provider-agnostic host transport in `xi.providers.runner`, and the
   frame carrying an SDK message is `message` (was `sdk-message`).
4. **(Optional, Phase 2) Containerize** — wrap the runner (nspawn/podman/microvm),
   swap stdio for a socket, mount creds + persist `~/.claude/projects` for
   resume. Protocol unchanged.

## Open questions / risks

- **Resume**: SDK session transcripts live under the runner's
  `~/.claude/projects`. Local child = host's `~/.claude` (same as now). If
  containerized later, that dir must be a persistent volume.
- **Runner lifecycle**: RESOLVED — per-turn spawn. One long-lived runner would
  interleave `sdk-message`/`tool-call` frames on a single stdout across
  concurrent rooms; per-turn spawn keeps one clean frame stream per turn and
  sidesteps crash/restart supervision. Revisit only if spawn latency bites.
- **Backpressure / message size**: base64 images inline in `start-turn` JSON —
  fine over stdio, just larger frames.
- **New-SDK API drift**: the newer SDK may change message shapes; the decoder
  refactor + fixtures in slice 1 make that drift visible and testable.
