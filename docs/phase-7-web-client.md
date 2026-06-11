# Phase 7 Plan — Web Client

Working plan for rebuild phase 7: rebuild the browser client on the new core
— same pure reducers, same EDN wire protocol, Replicant render layer.
Split across two sessions: **7a** (server gaps + app shell + chat view,
online-only milestone) and **7b** (home view, router, offline cache, unread).

Reference: master's `../xi/src/xi/web/` (~2.1k lines: `core` 143, `state` 28,
`router` 73, `cache` 160, `views` 788, `ws` 908) and docs
(`web-client.md`, `web-offline.md`, `frontend.md`).

Constraint reminders: `bb build` / `bb web:build` / `bb test`. The TUI is
still off-limits for the agent, **but the web client is testable**: run
`bun target/main.js server --headless &` in the background and drive the
browser via the chrome-devtools MCP tools (navigate, snapshot, screenshot,
console). Verify features in the browser before calling them done.

## Why this is smaller than it looks

Master's `web/ws.cljs` (908 lines) hand-rolled a JSON protocol with bespoke
client-side state transitions. In the rebuild, the web client is the same
thing the TUI client is: a renderer + input layer over mirrored room state.

- **Reuse `xi.client.ws-transport` as-is** — it is browser-safe
  (`js/WebSocket`, pure handler wrapping, `xi.wire` EDN encode/decode).
  Mirroring, forwarding, `:room/joined` snapshots, `:lobby/state` all come
  for free and are already exercised by the TUI client.
- **Reuse `xi.core.app/create-app`** — one atom, dispatch queue, coalesced
  render. The web render fn is Replicant's `render` of `(state → hiccup)`.
- **Reuse `xi.markdown.hiccup`** for assistant text (master already did).
- What's genuinely new: Replicant views ported to the new history-entry
  shapes, browser input layer, router, localStorage cache, and the
  server-side lobby/visibility gaps deferred from phase 5.

## Key design decisions

### One render fn, no component state

Render is `(fn [state dispatch!] → hiccup)` over the same state shape the
TUI renders: `[:rooms active-room :history]`, `:ui`, `:lobby`. Replicant
event handlers dispatch events — no `swap!` in views, no view-local atoms
(master's `web/state.cljs` app-state atom is replaced by the app's atom).

### Server owns sessions; lobby payload grows

Deferred from phase 5, needed by the home view:

- `:lobby/state` gains `:sessions` — saved sessions from disk (id, name,
  cwd, mtime, message count) merged with live-room status (`:active?`
  `:busy?` via room summaries). Server refreshes lobby clients on turn-end
  and room create/destroy (broadcast tap already does this for room events).
- `:room/join` accepts `:target {:session-id sid}` (resume a saved session
  into a new room) in addition to `"new"`/`"latest"`/room-id.
- `:client/update {:visible? bool}` — per-client visibility on the client
  map; pushover/done-notify suppression reads "any visible client in room".
- Response counts for unread dots: client sends
  `{:type :session/counts :session-ids […]}`, server replies with counts —
  client-only unread logic stays in localStorage (as on master).

### Static serving from the Bun server

`xi.server.ws` `:fetch` serves `resources/public` (index.html, css, js)
with content types, falling back to index.html for router paths
(`/chat/...`). The web client then lives at `http://localhost:7474` — no
shadow dev-http needed in production (dev-http 8100 stays for hot reload).
Resolve the public dir relative to the compiled script, not cwd.

### Offline model (unchanged from master)

- Hydrate from localStorage before the WS connects (instant paint).
- Keys: `xi/sessions`, `xi/messages` (per-session history), `xi/pending`
  (queued sends), `xi/last-room`.
- Backend wins: `:room/joined` snapshot / `:lobby/state` replace cache.
- Pending sends flush on reconnect; offline badge in header.
- Reconnect with backoff (master: 1s → 30s cap).

### What gets dropped/deferred

- Master's per-message JSON shapes — the cache stores the new history-entry
  maps (EDN strings), no format shim.
- Personal-agent web mode — after room policies exist (phase 8 candidate).
- `xi rooms` CLI listing (phase 5 leftover) — optional 7b cleanup, trivial
  via `:room/list`.

## Session 7a — server gaps + app shell + chat

Milestone: `xi server --headless`, open `localhost:7474`, chat with a
streaming agent — text deltas, thinking, tool-call blocks, abort.

1. ✅ **Server: static file serving** in `xi.server.ws` `:fetch`.
2. ✅ **Server: lobby sessions + visibility** — lobby payload gains
   `:sessions`; `:client/update` handler; `:room/join` by session-id
   (resume path via `:room/setup` → `:session/resumed`). Visibility
   *sending* from the client is 7b.
3. ✅ **Web app shell** (`xi.web.core`) — `create-app` + ws-transport
   `make-handlers` over the base pure handlers + Replicant render. Base
   handler requires are browser-safe; the `:web` build is clean.
4. ✅ **Chat view** (`xi.web.views`) — new entry kinds
   (`:user :text :thinking :tool-call :status :error :aborted`):
   markdown text, collapsible thinking/tool blocks, busy loader,
   auto-scroll (pin unless scrolled up). Images deferred to 7b.
5. ✅ **Input layer** — compose box → `:input/submit`, abort button →
   `:agent/abort`. Image paste deferred to 7b.
6. ✅ **Verify in browser** (chrome-devtools MCP) + update docs.

## Session 7b — home, router, offline

Milestone: full parity with master's web client per `docs/web-client.md`.

1. **Router** (`xi.web.router`) — ✅ history routing, `/` home,
   `/chat/:session-id`; navigation is a pure `:route/navigate` handler
   that emits history-push + room join/leave effects; route lives in the
   single app atom under `:web/route` (no separate router atom).
2. **Home view** — ✅ session list from `:lobby` (active dot, busy
   spinner, relative timestamps, offline badge, unread dots),
   new-session button (`:room/new`), back/switch via router.
3. **Offline cache** (`xi.web.cache`) — ✅ localStorage EDN,
   hydrate-before-connect, pending queue + flush, backend-wins on
   `:room/joined`/`:lobby/state`, reconnect backoff with `lastJoin` replay.
4. **Unread indicators** — ✅ watched counts in localStorage,
   `:session/counts` → `:session/counts-result` round-trip, clear on view.
5. **Compose drafts** per session — deferred (not yet implemented; the
   compose textarea is uncontrolled and read on submit).
6. **Visibility wiring** — ✅ `visibilitychange` → `:client/update`
   `{:visible?}`; server suppresses notifications when a visible client
   is attached.
7. Optional: `xi rooms` CLI; delete stale master-protocol notes from
   `docs/web-client.md` / `docs/web-offline.md` and rewrite for the new
   protocol. (deferred)
8. **Verify in browser**, update `docs/rebuild-plan.md` phase row + notes. ✅

## Verification checklist (agent-runnable)

- [x] `bb build` + `bb web:build` + `bb test` green
- [x] Headless server in background; browser connects, room renders
- [x] Send prompt → streamed text appears; tool-call block expands
- [x] Abort mid-turn from the web UI
- [ ] Two clients (TUI mirror logic already proves this, but: two browser
      tabs) see the same room state
- [x] Kill server → offline badge shows; restart → auto-reconnect with
      backoff and `lastJoin` room replay (history intact)
- [x] Reload page mid-session → cache hydrates instantly, then live state
- [x] Home lists saved sessions; deep-link `/chat/:id` resumes history;
      back button + popstate navigation work
