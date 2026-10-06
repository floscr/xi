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
  > yourself.
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
              :user    "root"            ;; this process' own user id
              :clients {client-id {:kind :tui/:web :visible? … :room-id … :user "alice"}}}
 :rooms      {room-id {:history  []        ;; chat history (see below)
                       :session  {:id … :provider-session-id … :name …}
                       :agent    {:busy? false :model "…" :provider :anthropic
                                  :queued []}
                       :members  {client-id {:user "alice" :platform "web"}} ;; presence
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
  :tool-call | :error | :aborted | :status …}`. A `:user` entry carries
  `:user`, the sender's user id (see [Users](#users)). Streaming deltas fold into
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
- `xi server [--headless] [--agent ID]` — server handlers + room
  manager + cleanup chains; non-headless additionally boots a local TUI
  client app in the same process, joining via WS like any remote client.
- `xi join [url]` / `xi create [url]` — TUI client over `ws-transport`
  (target `"latest"` / `"new"`).
- `xi prompt <text>` (aka `xi -p`) — one-shot headless run: the same core +
  server-side extensions (minus terminal-title), no renderer. A tap collects
  `:agent/text-delta` output and exits on `:agent/turn-end`; `--stream` writes
  tokens live. Its state is `:server` mode marked `:clientless?`, so dialogs
  resolve to their safe defaults. See [Prompt mode](#prompt-mode) below.
- The web client (`xi.web.core`) is the same assembly pattern in the
  browser — see [web-client-internals.md](web-client-internals.md).

Extensions compose into the assembly as data — see
[extensions.md](extensions.md).

## Event log

- Always-on in-memory ring buffer (~2k entries), large payloads elided,
  text deltas coalesced per turn. Feeds the `/logs` buffer and `/events`.
- Opt-in `--debug-events` writes full JSONL to
  `~/.pi/agent/logs/<session>.events.jsonl` via a buffered writer.

## Commands

Slash commands are data composed at assembly: `xi.commands/built-in-commands`
plus every extension's `:commands`, merged by `xi.cli` (built-ins win on a
name clash). `parse-input` routes editor text: `/name args` becomes
`:command/run {:name :args}`, anything else `:prompt/submit`.
`make-command-run` closes over the merged vector and calls the command's
handler with `{:room-id :args :commands}`; `all-commands` feeds TUI
completion and `/help`. A command handler is an ordinary pure handler; I/O
goes through effects. In client mode commands are forwarded like any event,
except `/quit` and `/reload`, which `ws-transport` handles locally. The user
list is in the guide ([commands](guide/commands.md)).

## Session tools

- **`/truncate`** (`xi.compaction`): `:compact/request` runs a summary turn
  through the Claude provider (always `COMPACT_MODEL`, resuming the room's
  provider session), then `[:session/new]` with `:after-prompt` starts a
  fresh session whose first user message is the summary in
  `<conversation-summary>`. `:keep-history?` keeps the old entries on screen
  flagged `:no-llm? true` above a divider; `:truncated-from` links the new
  session to the old one, and `xi.session/read-session-messages` follows the
  chain on resume (blocks tagged `:pre-truncation?`). `history->context` and
  `history->messages` skip `:no-llm?` entries, so the model never sees them.
  `abort-handler` is chained onto `:agent/abort`.
- **`/tree`** (`xi.commands`, `xi.tui.history-selector`): works on room
  `:history` directly. `:tree/navigate {:index :mode}` truncates the history,
  clears `[:session :provider-session-id]` and sets `:inject-history?`; the
  next turn starts a fresh provider session with the kept exchanges rendered
  into the system prompt by `xi.agent/history->context`. Once a provider
  session id lands, resume takes over. Nothing on disk changes.
- **`/trim`, `/rollover`, `/lineage`** (`xi.ext.resume`): `/trim` rewrites
  the Claude CLI transcript in place (backup next to it, placeholders citing
  backup file + line), keeping the session id; since every turn re-resumes
  with `--resume <id>`, it applies on the next turn. `/rollover` starts a
  fresh session with a `<session-lineage>` block of ancestor transcript paths
  (same `:truncated-from` chain as compaction). The extension adds a
  system-prompt note explaining the placeholders. Room state:
  `[:ext :resume {:pending …}]` for the trim preview.

## Users

Every connection belongs to a **user**: a plain string id, `"root"` by
default (`xi.util/user-id` normalizes claims; anything invalid is root).
There is no authentication — device pairing stays the only trust check — and
no roles; the core only tells users apart. Names, roles and real auth are
extension territory, keyed by the id.

- **Resolution** happens once, in the WS server's `admit!`:
  `clients.edn`'s `:user` for the device key (`xi clients user …`) wins,
  else the `:user` the client claimed in `:auth/hello` (TUI: `--user` /
  `XI_USER`; web: `localStorage xi-user`), else root. The local TUI key is
  implicitly trusted, so its claim stands.
- **State**: `[:connection :user]` is the process' own user (standalone
  input, the server's HTTP API and other clientless prompts act as it; a
  client sets it from `:auth/ok {:user}`). `[:connection :clients cid :user]`
  is the server registry. `[:rooms rid :members]` is presence: `client-id →
  {:user :platform}`, room-scoped so it mirrors, maintained by the room
  manager and broadcast as `:room/presence` on attach, leave and disconnect
  (a client switching rooms refreshes both). `state/own-user`,
  `state/event-user`, `state/room-users` are the accessors.
- **Wire**: the server stamps `:user` next to `:client-id` on every event a
  client sends, so the pure reducers (and every mirror re-running them)
  attribute it identically. `prompt-submit` stores it on the `:user` history
  entry and on queued prompts; a drained queue is attributed to its first
  prompt. Lobby room summaries carry `:users`.
- **Persistence**: a session records `:user` (its creator) in its metadata.
  Attribution of individual messages is in-memory only for now — a resumed
  transcript renders without senders, and the model is not told who speaks.
- **Rendering**: the TUI and web label a prompt with its sender when it is
  not the viewer's own user; the web chat topbar lists the other users
  attached to the room.
- **Avatars**: `xi.avatar` (pure, cljc) holds the public profile
  `{:name :avatar}` (config `:users`; `:avatar` must be an http(s) URL, so
  nothing else reaches an `<img src>`), initials and the id-derived hue. The
  server publishes `xi.users/public-profiles` (declared users + everyone in a
  room, never `:meta`) as `:profiles` on `:lobby/state`, which every client
  receives and mirrors at `[:lobby :profiles]`. `sidebar/room-people` turns a
  room's `:users` into avatar data for session cards (`:people`), empty while
  only one user is known so single-user servers look as before; the web
  `avatar-stack` draws them on cards and in the chat topbar. The payload also
  carries `:user-ids` (`users/declared-ids`: config users plus root); with more
  than one, the sidebar footer shows the user switcher, which sets
  localStorage `xi-user` and reloads (the claim `:auth/hello` sends).
- **User records and extension state**: `[:users id]` holds `{:id :name
  :meta :ui :ext}` server-side (never on the wire). `:name`/`:meta` come from
  config.edn `:users` (`xi.user-config`, validated, read-only); `:ui` and `:ext`
  are the persisted per-user state. `xi.users` loads the record at connect
  (`:user/loaded`, dispatched by `admit!` and at startup for the process' own
  user) and is the only writer of `:ext`: `set-ext-state!` validates (plain
  data, `xi.user-state/ext-value?`, 64 KB), persists via the store, then
  dispatches `:user/ext-set`. Extensions reach it through `xi.api.user`, which
  resolves the caller from its token, so an extension only touches its own
  entry; `xi.ext.user.guard` already drops handler changes outside the
  extension's own slices, which covers `:users`. `xi.server.ws` rejects the
  server-only events (`:user/*`, `:room/presence`, `:client/*`) when a client
  sends them, `:ext` is excluded from `xi.user-state/client-view` (what
  `:user-state/state` carries) and from `client-valid?` (what `:user-state/set`
  accepts). The acting user reaches tools as `:user` in the tool ctx
  (`state/turn-user`: the latest prompt's sender). `state/event-user` resolves
  an event's user: the stamp, else the sole user in its room, else the process'
  own user (slash commands that submit prompts run server-side with no stamp).
- **UI state**: the web client's browser-only state (theme, appearance,
  collapsed groups, preferred model, recent commands/skills) is per user.
  `xi.user-state` is the registry (known keys + validators, browser-safe);
  `xi.user-state.store` keeps one EDN file per user under
  `~/.config/xi/state/users/`. Clients send `:user-state/set {:key :value}`
  (roomless; the server's `:user` stamp decides whose state it writes, and the
  handler drops unknown keys and invalid values); the `:user-state/save`
  effect persists it and sends `:user-state/changed` to all of that user's
  devices, and `admit!` sends `:user-state/state` after `:auth/ok`. This is
  state, not configuration: config.edn, rules, MCP and extensions stay global.
  Server-global state that predates this (favorites, read state, hidden
  sessions, snippets) is unchanged. See
  [web-client-internals.md](web-client-internals.md).

## Room lifecycle and auth

- Rooms are created by `:room/join` targets (`"new"`, `"latest"`, a room
  id, `{:session-id sid}`), attached with `:room/attach` → `:room/joined`
  snapshot. A join may carry the web client's cached-history fingerprint so
  the snapshot elides what the client already has
  ([web-offline.md](web-offline.md)).
- **Auto-destroy**: a room closes when its last client leaves or disconnects
  while the agent is **idle**, or when a turn ends with no clients attached
  (`turn-end-room-cleanup`).
- **Never abort a busy room on disconnect.** A client disconnect is
  indistinguishable from navigating to another chat (iOS Safari drops the
  socket on every navigation), so any abort-on-disconnect, even behind a
  grace period, kills running agents. This has been reintroduced and
  reverted repeatedly (`50ac3fe` → `a74a7b5` → `cb88424` → `c5141fa` →
  `cd027c2`). `room-leave` and `client-disconnect-cleanup` may close a room
  only when it is idle and empty; never schedule a delayed `:agent/abort`.
- **Auth handshake** is transport-level (`xi.server.ws`), never dispatched
  into app state: `:auth/hello {:client-key :client-name :platform :user}` →
  `:auth/ok {:user}` | `:auth/pending {:code}` | `:auth/denied`. Pending clients get
  `:auth/required` for everything else; authed clients receive
  `:auth/request` and may `:auth/approve` / `:auth/deny`; the server also
  polls `~/.config/xi/clients.edn` every 2s so `xi clients approve` works
  from a shell. Cross-host `Origin` headers are refused at upgrade
  (hostnames only, ports ignored, so the dev-http page on :8100 can open
  :7474). Keys: `~/.config/xi/client-key` (local TUI, implicitly trusted),
  `clients.edn` (entries may carry `:user`, see [Users](#users)),
  `pending-clients.edn`, all mode 0600. The web client's key
  lives in `localStorage` (`xi-client-key`).
- **HTTP API**: `POST /api/rooms` (same fetch handler on the plain and TLS
  ports) provisions a room and optionally starts a turn; auth by client key
  header, skipped on an `--agent` server.
- **Crash guard**: `install-crash-guard!` (server only) logs
  `unhandledRejection` / `uncaughtException` to stderr and
  `~/.config/xi/crash.log` instead of exiting; typically an `EPIPE` from a
  runner subprocess pipe.

## Prompt mode

`xi prompt` is the standalone assembly minus the renderer and the
`terminal-title` extension (its ANSI escapes would corrupt stdout): a tap
collects `:agent/text-delta` (echoed live under `--stream`) and
`:agent/turn-end` flushes and exits. Initial state is `:server` mode marked
`:clientless?` (no client can ever attach), so dialogs resolve to their safe
defaults; a regular server keeps them open while no client is connected. No auto-titling turn.
`--no-store` runs against a throwaway `CLAUDE_CONFIG_DIR` and skips the Xi
session save. See `xi.cli/start-prompt!`.

## The clj tool's worker

SCI eval is synchronous and `(sh …)` uses `spawnSync`, so evaluating on the
server's event loop froze every room for the command's duration. `clj` and
`bb` post the request to a **per-room worker thread** (`target/main.js`
re-enters itself as a `node:worker_threads` Worker; `xi.cli/main`'s
`isMainThread` guard → `xi.ext.clj-worker` → `xi.ext.clj/eval-message`).
Each worker holds its own SCI runtimes (so `def` persists per room), is
spawned lazily and terminated on `:room/close`, `/clj reset` and
`/ext disable clj`. Gates that need the main thread (runtime path asks, git
holds) use a `gateRequest` message and block on `Atomics.wait` on a
`SharedArrayBuffer`, which the turn's abort flag also wakes. Loopback
sockets spawn a nested bridge worker owning the async `net.Socket` and
stream bytes through a ring buffer. Pre-scan + approval runs before eval
(`xi.ext.clj/approve`) and injects the approved set into the call's
arguments (`:_allowed`, `:_allowed-commands`, `:_allowed-bg`), which `sh`
re-checks at runtime. Tests: `test/xi/ext/clj_test.cljs`,
`clj_sandbox_test.cljs` (the interop escape corpus).
