# Xi Rebuild Plan

Status: **in progress** — work happens on branch `rebuild` in this worktree (`xi-next`).

## Phase status

| # | Phase | Status | Commit |
|---|-------|--------|--------|
| 1 | Scaffold (strip rewrite targets, green builds) | ✅ done | `aa94896` |
| 2 | Pure core (state/events/app/log/jsonl) | ✅ done | `96ea08a` |
| 3 | Provider layer (agent orchestration, claude, ollama) | ✅ done | `2e00b5d` |
| 4 | Standalone TUI (render-from-state, commands, sessions, compaction) | ✅ done | `f0aed74` |
| 5 | Connection layer (WS transports, rooms) | ✅ done | `289f0c7` |
| 6 | Extensions (new hook API, port all 18) | ✅ done | `62419b0`–`ce33f7f` |
| 7 | Web client (rebuild on new core) | ✅ 7a (online chat) + 7b (home/router/offline/unread) — plan: [phase-7-web-client.md](phase-7-web-client.md) | |
| 8 | Cutover (parity checklist, merge) | ✅ done 2026-06-11 — merged to `master`, `ui-refactor` retired (tag `archive/ui-refactor`), old worktree removed: [phase-8-cutover.md](phase-8-cutover.md) | |

### Implementation notes (phases 2–5, for continuity)

- **Namespaces**: `xi.core.state` (schema/constructors), `xi.core.events`
  (pure reducer, `core-handlers`), `xi.core.app` (`create-app` — the one
  impure shell: single atom, FIFO dispatch queue, effect interpreter, taps,
  change-gated microtask-coalesced render), `xi.core.log` (ring buffer,
  elision, delta coalescing — browser-safe), `xi.core.jsonl` (node-only
  debug writer), `xi.agent` (turn lifecycle handlers + `create-fx`),
  `xi.provider.claude`, `xi.provider.ollama`.
- **Handler contract**: `(fn [state event]) → {:state :effects} | nil`.
  Effects are data `[[:fx/type payload] …]`; fx handlers get
  `{:dispatch! :state}`. Built-in `:app/dispatch` effect re-dispatches an
  event (used to drain queued prompts through the normal code path).
- **History entries** (room `:history`): `{:kind :user|:text|:thinking
  |:tool-call|:error|:aborted …}` — deltas fold into the trailing open
  entry; `:agent/turn-end` finalizes (`:done? true`), clears busy, stores
  `:last-usage`/`:last-cost`, sets `[:session :provider-session-id]`
  (used as `:resume-session-id` on the next turn).
- **Providers**: `{:id kw :start-turn! (fn [opts] {:promise :abort!})}`.
  Routing: explicit `:provider` key wins, else `util/claude-model?`
  heuristic. Extension hooks inject via `:tool-gate` (async transform;
  nil blocks, `{:intercepted …}` short-circuits) — ext/core is NOT a
  provider dependency anymore. The claude session-state atom is gone.
- **In-flight turn handles** live in the `agent/create-fx` closure
  (runtime resources, not app state). Abort: `:agent/abort` event →
  `:provider/abort` effect → handle's `abort!`.
- **Phase 4 — TUI + commands + sessions + compaction** (4 commits:
  `94a3a2e` commands, `41ef344` compaction, `f0aed74` TUI+CLI wiring):
  - `xi.commands` — 12 slash commands as pure handlers + effects.
    `parse-input` routes editor text to `:prompt/submit` or
    `:command/run`; `messages->history` rebuilds history from session
    JSONL for `/resume`.
  - `xi.fx` — effect handlers for sessions (new/sync/list/load),
    `image/process` (client-side resize via `xi.image`), `models/fetch`
    (Claude + Ollama picker).
  - `xi.compaction` — pure handlers (`compact/request → done/failed`) +
    `create-fx` that runs a summary turn through the claude provider,
    resuming the room's provider session. Abort chained onto
    `:agent/abort`.
  - `xi.client.tui` — renderer + input layer. `create!` returns
    `{:render :effects}`. `render` is a fn of `(state dispatch!)` —
    syncs history blocks (memoized per entry via `identical?`), loader,
    active buffer (chat/logs/prompt), and completion menus. Zero
    component-local atoms; `ctx` is a JS object holding runtime
    resources (terminal refs, block cache) that mirrors app state.
    Emits `:render/start` / `:render/done` with timing. Effects:
    `:app/quit`, `:app/reload`, `:clipboard/copy`.
  - `xi.client.view` — entry→block builders for all 7 history kinds.
    Tool blocks: syntax/diff highlighting, live spinner+timer, streamed
    arg/result updates. `launch-header`, `logs-view`, `buffer-view`.
  - `xi.cli` — the assembly point. Merges handler maps
    (core+agent+commands+compaction) with chained `:agent/turn-end`
    (session sync) and `:agent/abort` (compaction cancel). Wires
    effects from 4 sources (providers, fx, compaction, TUI). Loads
    `~/.pi/agent/settings.json`, AGENTS.md, `--model`/`--debug-events`.
    Auto-resume after `/reload` via `XI_RELOAD_SESSION` env var.
  - **Not wired yet**: session tree recorder (tree branching/navigation
    deferred — basic session persistence works via provider session id).
- **Phase 5 — connection layer** (WS server/client, rooms):
  - **Wire protocol** (`xi.wire`): events as EDN strings (`pr-str`/
    `read-string`) — the protocol IS the event maps; both peers are cljs
    so keywords/nesting survive (master used JSON). `:remote?` is
    transport-local and never sent.
  - `xi.server.room-manager` — rooms as pure event handlers over the
    same state shape: membership = `[:connection :clients cid :room-id]`.
    `:room/join {:target "new"|"latest"|id}` resolves to `:room/attach`
    (existing) or a `[:room/setup]` effect (provision session + AGENTS.md,
    then `:room/create` + `:room/attach`). `:room/attach` replies with a
    `:room/joined` **room snapshot**; `:room/leave`, `:room/list`
    (responds `:lobby/state`). Auto-destroy: room closes when its last
    client leaves/disconnects while idle, or a turn ends with no clients
    (chains: `client-disconnect-cleanup` *before* core disconnect,
    `turn-end-room-cleanup` after turn-end). Room ids derive from the
    event stamp (pure); `make-room` gained `:created` for "latest".
  - `xi.server.ws` — Bun WS server. `create-server` returns `{:fx :start!}`
    (sockets + effects share a closure; the app wires in via `start!`).
    **Broadcast is a tap**: every processed event with a `:room-id` is
    echoed to that room's clients (sender included — clients never apply
    their own input locally); lobby (roomless) clients get `:lobby/state`
    refreshes. Incoming room events get `:room-id` forced to the sender's
    joined room. Server-side stubs for TUI-owned effects
    (`:app/quit`/`:app/reload`/`:clipboard/copy`).
  - `xi.client.ws-transport` — client mode = forward + mirror.
    `make-handlers` wraps the standalone pure handler map: local events →
    `[:ws/send event]` (no local state change); `:remote?`-tagged
    broadcasts → same reducers with effects stripped (whitelist:
    `:clipboard/copy` still runs client-side, so `/debug` works).
    `/quit` + `/reload` are intercepted locally (act on the client
    process). Client-only handlers: `:room/joined` installs the snapshot
    + sets `:active-room`, `:room/left`, `:lobby/state` (stored under
    `:lobby`). The mirror is exact because both sides run the same pure
    reducers in the same (server) order, seeded by the snapshot.
  - `xi.cli` — `xi server [--headless --port N]` (server app: standalone
    handlers + rm handlers + cleanup chains, provider/session/WS effects,
    no renderer; non-headless additionally boots a local TUI client app
    in the same process joining `"new"` via WS — same code path as any
    remote client), `xi join [url]` (target `"latest"`), `xi create
    [url]` (target `"new"`). The TUI client itself is **unchanged** from
    phase 4 — it renders mirrored state and dispatches the same events.
  - **Deferred**: `:visibility` tracking, dictation, session lists in the
    lobby payload (web client, phase 7); `xi rooms` CLI listing.
    Personal-agent room policy landed post-phase-7: `--personal-agent-only`
    makes `:room/setup` provision PA rooms (PA prompt, PA session dir,
    `[:agent :personal-agent?]` flag drives provider tool restriction).
- **Old implementation reference**: `../xi` worktree (master).
- **Phase 6 — extensions** (6a: `62419b0`–`df7c79c`; 6b: `b701298`–`ce33f7f`):
  - **Extension = data map**: `:id :init :handlers :fx :event-hooks
    :tool-gate :tool-definitions :tool-registry :commands :system-prompt
    :keybindings :prompt-badge :on-shutdown`. No registration atoms or
    global state — compose at assembly time.
  - **State scoping**: room-scoped `[:rooms rid :ext <id>]` (mirrors via
    `:room/joined`); process-local `[:ext <id>]` (never crosses the wire).
  - **Composition** (`ext/compose`): merges extensions into one assembly
    map; `ext/merge-handlers` chains extension handlers after the base
    handler for each event type.
  - **Tool-gate**: async chain `(fn [tool-call ctx])` → tool-call | nil |
    `{:intercepted true :result …}`. Gate ctx:
    `{:dispatch! :get-state :room-id :cwd :confirm!}`. Tool exec-fns
    receive only `{:cwd}` — no app concerns.
  - **Dialogs**: `ext/create-dialogs` returns `ask!`; pushes data into
    room `:ui :dialogs`, rendered by TUI; response resolves promise.
    Headless/server safe-defaults (false for :confirm).
  - **Per-mode assembly** (xi.cli): `server-extensions` (all tool/gate/
    state extensions), `client-extensions` (dictation); standalone
    composes both. 16 extensions ported:
    - Batch 1 (tools): kb, web, perplexity, commit, clj-surgeon, gtd
    - Batch 2 (gates): permission-gate, todo-intercept
    - Batch 3 (client-side): terminal-title, clipboard-image, projects,
      skills
    - 6a (stateful): plan-mode, done-notify, pushover, dictation
  - **Deviations**: `parmezan` absorbed into clj-surgeon's auto-lint;
    `sub-project` dropped (stub with no behavior); personal-agent room
    policy landed later (see phase 5 note — core plumbing was already in
    place, only the provisioning edge was missing); `projects` drill-down (Tab→files) deferred (needs
    callback-based menus); `set-session-name!` dropped from gtd picker
    (no equivalent event in xi-next).
- **Phase 7a — web client (online chat)**:
  - **Same core, browser mode**: `xi.web.core/init!` wires
    `app/create-app` (`:mode :client`) + `ws-transport/make-handlers`
    over `events/core-handlers + agent/handlers + commands/command-handlers
    + compaction/handlers` (no node-coupled turn-end/abort chains — effects
    are stripped on mirror, so the bare merge suffices for a forward/mirror
    client). Connects to `ws://location.host`, joins target `"latest"`.
  - **Static serving**: `xi.server.ws` `:fetch` serves `resources/public`
    (Bun.file content-types), resolves the public dir relative to the
    compiled script (`__dirname/../resources/public`), SPA-falls back to
    `index.html` for extension-less paths, 403 on traversal. Same Bun
    server as the WS upgrade — web client lives at `:7474`.
  - **Lobby/resume gaps** (deferred from phase 5): lobby payload gains
    `:sessions` (read lazily, only when roomless clients exist);
    `:room/list` routes through a `:lobby/send` effect (pure handlers
    can't read fs); `:room/join` accepts `{:session-id sid}` →
    `:room/setup` creates the room then dispatches `:session/resumed`;
    `:client/update {:visible?}` sets per-client visibility (added to
    `roomless-types` — connection-level, never broadcast).
  - **Views** (`xi.web.views`): pure `(state → hiccup)` with `dispatch!`
    threaded through closures, rendered by Replicant. No view-local
    atoms — collapsible tool/thinking blocks use `<details>`/`<summary>`
    (Replicant only writes changed attrs, so manual toggles survive
    re-render) and the compose box is an uncontrolled textarea read on
    submit. Entry kinds map to `.post--{user,assistant,tool}`.
- **Phase 7b — web client (home, router, offline, unread)**:
  - **Router as events** (`xi.web.router`): route lives in the single app
    atom under `:web/route` (web-only key, no separate router atom);
    `:route/navigate` is a pure handler that sets the route and emits
    `[:history/push …]` plus room join/leave dispatches. `/` → home,
    `/chat/:session-id` → chat. `popstate` re-dispatches navigate with
    `:replace? true`. An `already?` guard skips re-join when the target
    session is already active.
  - **Home view** (`xi.web.views/home-view`): route-driven `root-view`
    switches home vs chat. Lists orphan rooms + saved sessions from
    `:lobby` as `.project-card`s (icon spinner/active-dot, relative
    timestamps, `.unread-dot`), with a new-session button dispatching
    `:room/new` (sets route to chat with a nil session id; a tap replaces
    the URL with the real id once `:room/joined` arrives).
  - **Offline cache** (`xi.web.cache`): localStorage EDN (pr-str /
    read-string). `hydrate` seeds `:web/route`, `:web/watched`, `:lobby`
    and `:web/cache` for a deep-linked session before the socket opens;
    an app tap persists lobby + active room (keyed by session id) on a
    whitelist of event types; the backend wins on `:room/joined` /
    `:lobby/state`. Chat falls back to cached history while a room rejoins.
  - **Reconnect** (`ws-transport`): opt-in `:reconnect?` with 1s→30s
    backoff and an in-memory pending-send queue. Transport joins with a
    nil target so the router drives joins; `lastJoin` is set per
    `:room/join`, cleared on `:room/leave`, and replayed on reopen so a
    dropped connection restores the correct room. The TUI keeps
    exit-on-disconnect by not opting into `:reconnect?`.
  - **Unread**: server `:session/counts` → `:session/counts-result`
    round-trip (roomless-typed); client compares `:web/response-counts`
    against `:web/watched` (localStorage), marking read on view via
    `:session/mark-read` + `:cache/watch`.
  - **Visibility**: client `visibilitychange` listener dispatches
    `:client/update {:visible?}` so the server suppresses notifications
    while a visible client is attached.
  - **Deferred from 7b**: per-session compose drafts (landed in phase 8,
    `c025be0`), image paste/thumbnails (landed, `1ec0bdb`), `xi rooms` CLI
    (still open), stale doc rewrite (done during phase 8 — see
    [phase-8-cutover.md](phase-8-cutover.md)).

## Why

Xi works well but adding features has become cumbersome. Root causes in the
current codebase:

- **Scattered mutable state**: `client/tui.cljs` holds ~30 ad-hoc atoms,
  `runtime.cljs` ~10, `ext/core.cljs` has 8 handler-registry atoms. Logic and
  mutation are interleaved everywhere.
- **Host/client vs standalone split**: standalone mode predates the
  connection/room schema, so every feature has to handle two worlds.
- **Event bus exists but is bypassed**: most flows are direct fn calls and
  handler atoms, so behavior is hard to trace and extend.
- **Claude-specific core**: the bridge is welded in; providers aren't
  swappable.

## Goals (code style contract)

1. **One state atom per process.** No feature-local atoms.
2. **Pure core.** All logic is `(handle-event state event) → {:state :effects}`.
   No mutation or `reset!` inside functions; side effects only in the effect
   interpreter.
3. **Unified connection/room schema everywhere.** Standalone is just "not
   connected to anything" — same data shape, same code paths.
4. **Everything via events.** Prompts, agent output, shortcuts, dialogs,
   room switches, extension hooks, renders.
5. **Providers are pluggable.** Claude/bridge is one provider among others.
6. **Sessions unchanged.** Same on-disk formats and `~/.pi/agent/` paths —
   old sessions resume in the rebuild.

## Architecture

### State schema

```clj
{:connection {:id      #uuid
              :mode    :standalone | :server | :client
              :clients {client-id {:kind :tui/:web :visible? ...}}}
 :rooms      {room-id {:history  []          ;; event-sourced chat history (local cache)
                       :session  {...}       ;; current session ref
                       :agent    {:busy? false :model "..." :provider :claude}
                       :ui       {:dialogs [] :buffers {} :active-buffer :chat}}}
 :active-room room-id}
```

- Standalone = one local connection, one room, connected to nothing.
- Server mode hosts N rooms; client mode mirrors remote rooms into the same shape.
- Transports (WS server/client) are pure event forwarders — serialize events
  across the wire, nothing else.
- Per-room `:ui` holds dialogs and extra buffers (e.g. per-room logs) so UI
  state is data, not component-local atoms.

### Event system

- Reducer pattern (re-frame-style, no re-frame dependency):
  `dispatch!` is the only impure entry point — swaps the atom, runs effects
  through the interpreter, schedules render.
- Effects (`[:provider/start-turn ...]`, `[:session/append ...]`,
  `[:ws/send ...]`) dispatch new events on completion; they never touch state.
- Naming convention: `:prompt/submit`, `:agent/text-delta`, `:room/join`,
  `:ui/dialog-open`, `:render/done`.
- Extensions hook in via interceptor-style transform chains on events
  (return transformed event, or nil to block — same semantics as today's
  hooks, new plumbing).

### Event log (debuggability without blowup)

- Always-on **in-memory ring buffer**, capped ~2k entries.
- Large payloads elided: text deltas coalesced per turn, image data reduced
  to byte counts.
- Opt-in `--debug-events`: full JSONL appended to
  `~/.pi/agent/logs/<session>.events.jsonl` via buffered writer (flush on
  idle/exit) — no sync-write slowdowns.
- Ring buffer feeds a per-room `:logs` buffer for in-TUI debugging.

### Rendering

- Render is a pure fn of room state → component tree, scheduled after each
  dispatch batch, microtask-coalesced (a burst of deltas = one paint).
- Renderer emits `:render/start` / `:render/done` events with timing into the
  same log — render bugs are debuggable like everything else.
- TUI components become stateless; current component-local atoms collapse
  into `:rooms → :ui`.

### Providers

A provider is a map of fns (like extensions, but for inference):

```clj
{:id :claude
 :models      (fn [] ...)
 :start-turn! (fn [ctx dispatch!] ...) ;; emits :agent/* events
 :abort!      (fn [ctx] ...)
 :compact!    (fn [ctx] ...)}          ;; optional
```

- Registered in a provider registry; room's `:agent :provider` selects one.
- `provider/claude.cljs` encapsulates all SDK/bridge quirks
  (`.close()`, version pin, stream-json handling).
- Ollama ports to the same interface.

### Sessions

Copied as-is: `session.cljs`, `session/tree.cljs`, `tree_recorder.cljs`,
`format.cljc`, `sync.cljc`. The tree recorder becomes a plain event
subscriber.

### Web client

**Rebuilt** on the new architecture (not ported): same reducer/effect/event
model as the TUI — one app-state atom, pure handlers, render-after-events.
TUI and web become two renderers + input layers over the same core; the WS
protocol is just the event transport both use.

## Copy vs rewrite

| Copy (mostly as-is) | Rewrite |
|---|---|
| `session/*`, `tools/*`, `highlight/*`, `util`, `compaction`, `system_prompt`, TUI primitives (`terminal`, `markdown`, `editor`, low-level components) | `runtime`, `cli`, `server/*`, `client/*`, `ext/core` (→ event hooks), `provider` (→ registry), `web/*` (rebuilt on new core) |

All 18 extensions ported onto the new hook/event API. Extensions that hold
their own state (`dictation`, `pushover`, `plan_mode`, `done_notify`) move
that state into app-state under an `:ext` key.

## Phases

1. **Scaffold** — worktree ✅, bb tasks, shadow-cljs, copy leaf namespaces,
   compile green.
2. **Core** — state schema, reducer, dispatch, effect interpreter, event log.
   Heavy unit tests (pure core = cheap tests).
3. **Provider layer** — registry + Claude provider + ollama; agent loop as
   effects.
4. **Standalone TUI** — render-from-state TUI, dialogs/buffers from room
   `:ui`, commands as events.
   *Milestone: daily-drivable standalone xi.*
5. **Connection layer** — WS server/client as event transports, rooms,
   join/create/list.
   *Milestone: parity with `xi server` / `join` / `create`.*
6. **Extensions** — new ext API, port all 18.
7. **Web client** — rebuild on new core (shared reducer model, web renderer +
   input layer), including offline/cache behavior.
8. **Cutover** — parity checklist against current master, merge `rebuild`
   into master.

## Decisions log

- Worktree `../xi-next`, branch `rebuild`, same `xi.*` namespaces (clean
  merge/diff at cutover). ✅
- Event log: in-memory ring buffer default + opt-in `--debug-events` JSONL. ✅
- Web client: full rebuild on new architecture, done after TUI/connection
  layers stabilize. ✅
- Sessions/data formats: unchanged, backward compatible. ✅
