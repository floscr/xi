# Xi Rebuild Plan

Status: **approved** — work happens on branch `rebuild` in this worktree (`xi-next`).

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
