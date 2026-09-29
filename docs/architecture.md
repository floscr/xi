# Architecture

Xi is an event-driven system with one state atom per process. Every feature
— prompts, agent output, slash commands, dialogs, room switches, renders —
flows through the same dispatch loop as plain event maps. The TUI, the WS
server, and the browser client are all assemblies of the same pure handlers.

## Core loop

```
                 dispatch! (the only impure entry point)
                      │
                      ▼
          ┌───────────────────────┐
          │  FIFO dispatch queue   │   xi.core.app/create-app
          │  (one event at a time) │
          └──────────┬────────────┘
                     ▼
     handler: (fn [state event]) → {:state … :effects […]} | nil
                     │
        ┌────────────┼─────────────────┐
        ▼            ▼                 ▼
   swap! atom   effect interpreter   taps (observers:
                [:fx/type payload]   WS broadcast, cache,
                → may dispatch!      event log, …)
                  new events
                     │
                     ▼
        change-gated, microtask-coalesced render
        (a burst of deltas = one paint)
```

- **Handlers are pure.** `(handle state event)` returns a new state and/or
  effect data, or `nil` for "not handled / no change". No mutation, no I/O.
- **Effects are data.** `[[:provider/start-turn …] [:session/sync …]]` —
  interpreted by effect handlers that receive `{:dispatch! :state}`. Effects
  dispatch new events when they complete; they never touch state directly.
  The built-in `:app/dispatch` effect re-dispatches an event (used e.g. to
  drain queued prompts through the normal code path).

  > **Pitfall — effects are not dispatchable as events.** Events
  > (`(dispatch! {:type :foo …})`, handled by pure reducers) and effects
  > (`[:effect/name {…}]` in a handler's `:effects`, run by the `:fx` map) are
  > separate namespaces of keywords. Dispatching an effect *as* an event —
  > e.g. `(dispatch! {:type :image/process …})` — matches no event handler and
  > is **silently dropped** (no error; the action just never happens). If you're
  > in fx/side-effect code and need what an effect does, call the underlying
  > pure fn directly rather than dispatching the effect. Example: `:image/process`
  > resizes then dispatches `:prompt/submit`; from fx code, resize inline via
  > `xi.image/process-images` and dispatch `:prompt/submit` with `:images`
  > yourself. (This bit the element-picker extension — see
  > [element-picker.md](element-picker.md).)
- **Taps observe** every processed event (with the post-event state). The WS
  server's broadcast, the web client's localStorage persistence, and the
  event log are all taps.
- **Renderers emit events too**: `:render/start` / `:render/done` with
  timing, so render bugs are debuggable like everything else.

### Namespaces

| Namespace | Role |
|---|---|
| `xi.core.state` | State schema + constructors |
| `xi.core.events` | Core pure handlers (`core-handlers`) |
| `xi.core.app` | `create-app` — the one impure shell (atom, queue, interpreter, taps, render scheduling) |
| `xi.core.log` | In-memory event ring buffer (browser-safe) |
| `xi.core.jsonl` | Opt-in `--debug-events` JSONL writer (node-only) |
| `xi.agent` | Agent turn lifecycle handlers + provider effects |
| `xi.commands` | Slash commands + input routing |
| `xi.fx` | Session/image/model effect handlers |
| `xi.wire` | Transit wire protocol |
| `xi.cli` | Assembly point — merges handler maps + effects per mode |

## State schema

```clojure
{:connection {:id      uuid
              :mode    :standalone | :server | :client
              :clients {client-id {:kind :tui/:web :visible? … :room-id …}}}
 :rooms      {room-id {:history  []        ;; chat history (see below)
                       :session  {:id … :provider-session-id … :name …}
                       :agent    {:busy? false :model "…" :provider :anthropic
                                  :queued []}
                       :cwd      "/path"
                       :ext      {ext-id {…}}   ;; room-scoped extension state
                       :ui       {:dialogs [] :buffers {} :active-buffer :chat
                                  :menu … :pending-images []}}}
 :active-room room-id
 :ext         {ext-id {…}}    ;; process-local extension state (never on the wire)
 :lobby       {…}}            ;; client mode: rooms/sessions list from server
```

- **Standalone is just "not connected"** — one local room, same shape, same
  code paths as server/client modes.
- **History entries** are maps `{:kind :user | :text | :thinking |
  :tool-call | :error | :aborted | :status …}`. Streaming deltas fold into
  the trailing open entry; `:agent/turn-end` finalizes it (`:done? true`),
  clears busy, stores `:last-usage`/`:last-cost`, and records the provider
  session id (used as `:resume-session-id` next turn).
- Per-room `:ui` holds dialogs, buffers, and menus as data — components have
  no local atoms.

## Event naming

`:domain/action` keywords: `:prompt/submit`, `:input/submit`,
`:agent/text-delta`, `:agent/turn-end`, `:agent/abort`, `:command/run`,
`:room/join`, `:room/joined`, `:lobby/state`, `:ui/menu-open`,
`:session/resumed`, `:compact/request`, `:render/done`, …

## Connection layer

The wire protocol (`xi.wire`) is the event maps themselves, serialized as
transit strings (JSON flavour). Both peers are ClojureScript, so
keywords and nesting survive without JSON shims. `:remote?` is
transport-local and never sent.

### Server (`xi.server.ws`, `xi.server.room-manager`)

- Bun WS server; the same Bun server also serves the web client's static
  files from `resources/public` (SPA fallback to `index.html`).
- **Broadcast is a tap**: every processed event carrying a `:room-id` is
  echoed to that room's clients — *including the sender*. Clients never
  apply their own input locally; they see it when the server confirms it.
- Rooms are pure event handlers over the same state shape. Membership is
  `[:connection :clients cid :room-id]`. `:room/join {:target
  "new"|"latest"|room-id|{:session-id sid}}` resolves to `:room/attach`
  (existing room) or provisions a new one; `:room/attach` replies with a
  `:room/joined` **room snapshot**. Roomless clients get `:lobby/state`.
- Rooms auto-destroy when the last client leaves/disconnects while idle, or
  a turn ends with no clients attached.

### Client (`xi.client.ws-transport`)

Client mode = **forward + mirror**. `make-handlers` wraps the standalone
pure handler map:

- Locally-originated events → `[:ws/send event]`, no local state change.
- `:remote?`-tagged broadcasts → the same pure reducers, with effects
  stripped (whitelisted client-side effects like `:clipboard/copy` still
  run). The mirror is exact because both sides run the same reducers in the
  same (server) order, seeded by the `:room/joined` snapshot.
- `/quit` and `/reload` are intercepted locally (they act on the client
  process).
- Optional `:reconnect?` (used by the web client): 1s→30s backoff, pending
  send queue, replays the last `:room/join` on reopen.

## Providers

A provider is a map: `{:id kw :start-turn! (fn [opts] {:promise :abort!})
:list-models! (fn [] Promise<[id…]>)}`. Which providers load is declared in
`src/xi/config.cljc` (like extensions); `xi.cli` derives the id → provider
lookup from that vector.

- Routing: an explicit `:provider` on the room's agent wins, otherwise the
  model-name heuristic in `xi.util/provider-for-model` picks one (shared with
  the browser build).
- In-flight turn handles live in the `agent/create-fx` closure (runtime
  resources, not app state). Abort: `:agent/abort` event →
  `:provider/abort` effect → handle's `abort!`.
- `xi.providers.anthropic` runs the Claude Agent SDK in the out-of-process
  `packages/providers/anthropic/` (tool calls proxy back to the host) — see
  [mcp-tool-bridge.md](mcp-tool-bridge.md).

## Assembly (`xi.cli`)

Each mode merges handler maps (core + agent + commands + compaction +
extensions) and wires effect handlers from its sources (providers, fx,
compaction, TUI, WS):

- `xi` — standalone: all handlers, local TUI renderer (see
  [tui-rendering.md](tui-rendering.md)).
- `xi server [--headless] [--personal-agent-only]` — server handlers + room
  manager + cleanup chains; non-headless additionally boots a local TUI
  client app in the same process, joining via WS like any remote client.
- `xi join [url]` / `xi create [url]` — TUI client over `ws-transport`
  (target `"latest"` / `"new"`).
- `xi prompt <text>` (aka `xi -p`) — one-shot headless run: the same core +
  server-side extensions (minus terminal-title), no renderer. A tap collects
  `:agent/text-delta` output and exits on `:agent/turn-end`; `--stream` writes
  tokens live. Dialogs run in `:server` mode with no clients, so they resolve
  to their safe defaults. See [prompt-mode.md](prompt-mode.md).
- The web client (`xi.web.core`) is the same assembly pattern in the
  browser — see [web-client.md](web-client.md).

Extensions compose into the assembly as data — see
[extensions.md](extensions.md).

## Event log

- Always-on in-memory ring buffer (~2k entries), large payloads elided,
  text deltas coalesced per turn. Feeds the `/logs` buffer and `/events`.
- Opt-in `--debug-events` writes full JSONL to
  `~/.pi/agent/logs/<session>.events.jsonl` via a buffered writer.
