# Web Client

Browser-based client for xi. It is **the same pure core as every other
mode** — `xi.core.app` in `:client` mode, wired through
`xi.client.ws-transport` — with [Replicant](https://github.com/cjohansen/replicant)
as the renderer. The browser only renders state and collects input.

## Quick Start

```bash
bb web:build                # compile the :web (browser) build
xi server --headless        # or `xi server` for server + local TUI
```

Open `http://localhost:7474` — the same Bun server that hosts the WS
endpoint serves the compiled client from `resources/public` (SPA fallback
to `index.html`).

## Architecture

```
┌────────────────────────────────────────────────────────────┐
│ Browser                                                     │
│                                                             │
│   xi.core.app/create-app (:mode :client)                    │
│   one atom · pure handlers · effect interpreter · taps      │
│        ▲                                  │                 │
│        │ mirror (:remote? events,         │ render          │
│        │ effects stripped)                ▼                 │
│   xi.client.ws-transport            Replicant ← views.cljs  │
│        │ forward (local events            (pure state →     │
│        ▼  → [:ws/send])                    hiccup)          │
│   WebSocket (transit event maps, xi.wire)                   │
└────────┼───────────────────────────────────────────────────┘
         ▼
   xi server (:7474) — rooms, agent, sessions
```

- **Handlers**: the browser merges the same pure handler maps the server
  uses (`core-handlers` + `agent` + `commands` + `compaction`) — no
  node-coupled chains, since effects are stripped on mirror anyway — plus
  web-local handlers (router, compose, lightbox, unread).
- **Forward + mirror**: locally-originated events are sent to the server;
  the server broadcasts every room event (sender included) and the client
  applies them through the same reducers, seeded by the `:room/joined`
  snapshot. See [architecture.md](architecture.md).
- **Wire protocol**: there is no separate web protocol — the transit-serialized
  event maps (`xi.wire`) *are* the protocol, identical to the TUI client.

## Features

### Home view (`/`)

Lists live rooms and saved sessions from the lobby mirror (`:lobby`):

- busy spinner / active-dot for sessions with a live room
- unread dot when the server's response count exceeds the watched count
- relative timestamps; offline badge when disconnected
- new-session button (`:room/new` — joins target `"new"`, the URL is
  back-filled with the real session id once `:room/joined` arrives)

### Chat view (`/chat/:session-id`)

- Streaming text/thinking/tool entries rendered from room `:history` —
  the same entry maps the TUI renders
- Thinking blocks and interesting tool blocks expanded by default
  (`<details>` — Replicant only writes changed attrs, so manual toggles
  survive re-renders)
- **Viewer mode** (overflow menu → "Viewer mode", `:web/viewer-mode?`,
  persisted to `localStorage "xi-viewer-mode"`): collapses tool posts
  (read/write/clj/…) and thinking blocks to header-only rows and folds each
  run of consecutive ones into a single `.viewer-tool-group` box. A run breaks on any text
  entry or on a tool with a *pending* permission ask (which stays expanded
  for its Allow/Deny buttons). An already-answered tool joins the group and
  shows a decision icon (✓/✗) at the right of its header. Each collapsed
  header is still an individual `<details>` you can click to expand in place
  (`group-viewer-items` in `xi.web.views`)
- **Timeline virtualization**: only the last 60 entries render; "Show
  earlier" expands by 40 (`:web/timeline-window`, reset on navigation)
- **Per-session compose drafts** (`:web/drafts`, keyed by session id;
  `:new` before the first join) — unsent text survives navigation
- **Images**: paste / file-picker attachments (client-side resize via
  `xi.image`), thumbnail strip, fullscreen lightbox (`:web/lightbox`)
- Markdown via `xi.markdown.hiccup`; code blocks highlighted with the
  bundled browser grammars ([syntax-highlighting.md](syntax-highlighting.md))
- Auto-scroll pinned to bottom unless you scroll up
- Sending from a cached (not-yet-joined) session stashes the message
  (`:web/pending-submit`) and fires it after `:room/joined`

### Routing

The router lives **in the app atom** (`:web/route`) — `:route/navigate` is
a pure handler that sets the route and emits `[:history/push]` plus room
join/leave dispatches (`xi.web.router`). `popstate` re-dispatches navigate
with `:replace? true`. Deep-linking `/chat/:sid` hydrates from cache, then
joins/resumes the session over WS.

### Unread tracking

Server round-trip `:session/counts` → `:session/counts-result`
(`:web/response-counts`), compared against `:web/watched` (localStorage).
Viewing a session marks it read (`:session/mark-read` + `:cache/watch`).
The home lobby tap requests counts whenever `:lobby/state` arrives.

### Visibility

A `visibilitychange` listener dispatches `:client/update {:visible? …}` so
the server suppresses notifications (e.g. Pushover) while a visible client
is attached.

### Offline & reconnect

See [web-offline.md](web-offline.md): localStorage cache hydrates before
the socket opens; the transport reconnects with 1s→30s backoff and replays
the last room join.

## Web-only state keys

All under the same app atom, never sent over the wire:

| Key | Contents |
|---|---|
| `:web/route` | `{:page :home/:chat :session-id …}` |
| `:web/drafts` | `{draft-key text}` compose drafts per session |
| `:web/compose-images` | staged image attachments |
| `:web/timeline-window` | virtualization window size |
| `:web/viewer-mode?` | collapse tool posts into grouped headers |
| `:web/lightbox` | open image src or absent |
| `:web/watched` | `{session-id count-when-last-seen}` |
| `:web/response-counts` | `{session-id count}` from the server |
| `:web/cache` | hydrated per-session history for deep links |
| `:web/pending-submit` | message stashed until `:room/joined` |
| `:web/connected?` | transport status |
| `:lobby` | rooms + sessions mirror (shared shape with TUI client) |

## Source files

```
src/xi/web/
  core.cljs    — entry: assembly (handlers/effects/taps), Replicant render,
                 auto-scroll, visibility listener
  views.cljs   — pure views (state → hiccup): home, chat, compose, lightbox
  router.cljs  — route parsing, :route/navigate handler, History API effect
  cache.cljs   — localStorage offline cache (hydrate + persist tap)

src/xi/client/ws_transport.cljs — shared WS transport (forward+mirror,
                                  reconnect, pending sends)
src/xi/server/ws.cljs           — WS server + static file serving

resources/public/
  index.html      — shell (loads compiled JS)
  css/style.css   — app styles
  theme.css       — built clj-ui-framework theme (see frontend.md)
```

UI components come from [clj-ui-framework](frontend.md) (`ui.button`,
`ui.icon`, `ui.spinner`, `ui.lightbox`, `ui.form`, …).

## Known issues

- Deep-link *reload* into a chat URL can occasionally join a room whose
  resumed history is empty (server-side room-resume race — tracked in
  [phase-8-cutover.md](phase-8-cutover.md)).
