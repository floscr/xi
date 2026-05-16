# Web Client Offline Support

The web client works without a live WebSocket connection. Sessions and messages are cached in `localStorage` so they're available immediately on page load, even when the backend is down.

## Design Principles

- **Messages are never lost.** Sends while offline are queued as pending messages and flushed on reconnect.
- **Backend wins.** When the server sends fresh data (history replay, handshake), it replaces the local cache.
- **Instant startup.** The UI hydrates from cache before the WebSocket connects, so you see content immediately.

## Architecture

```
┌─────────────────────────────────────────────────────┐
│  Browser                                             │
│                                                      │
│  ┌──────────┐   ┌──────────┐   ┌──────────────────┐ │
│  │ app-state│←──│  ws.cljs │──→│  WS Server       │ │
│  │  (atom)  │   │          │   │  (:7474)          │ │
│  └────┬─────┘   └────┬─────┘   └──────────────────┘ │
│       │              │                                │
│       │         ┌────┴─────┐                          │
│       │         │cache.cljs│                          │
│       │         │          │                          │
│       │         └────┬─────┘                          │
│       │              │                                │
│       │         ┌────┴──────────┐                     │
│       │         │  localStorage │                     │
│       │         │               │                     │
│       │         │  xi/sessions  │                     │
│       │         │  xi/messages  │                     │
│       │         │  xi/pending   │                     │
│       │         │  xi/last-room │                     │
│       │         └───────────────┘                     │
│  ┌────┴─────┐                                         │
│  │ views.cljs│  (Replicant rendering)                 │
│  └──────────┘                                         │
└─────────────────────────────────────────────────────┘
```

## Storage Layout

Cache keys mirror the filesystem session path pattern (paths → JSON blobs), defined in `xi.session.format` (cljc):

| Key | Contents |
|---|---|
| `xi/sessions-list` | Array of session summaries from server handshake |
| `xi/messages/{session-id}` | Array of rendered messages for a session |
| `xi/pending-messages` | Queue of messages to send on reconnect |
| `xi/last-room` | `{room-id, session-id}` of the last joined room |

All values are JSON. Keywords survive the round-trip via `keywordize-msg-type` on read.

## Flows

### Startup (page load)

```
1. hydrate-from-cache!
   ├── Load cached sessions → show home view immediately
   ├── Load pending messages → restore queue
   └── If last-room exists → restore chat view with cached messages
2. ws/connect!
   ├── On connect → server sends :waiting-for-join
   ├── If was in chat → auto-rejoin room (or resume session)
   └── If was on home → show home with fresh data (replaces cache)
```

### Sending a message while online

```
1. User submits text
2. Message added to timeline optimistically (before server echo)
3. ws/dispatch! sends via WebSocket
4. Server echoes :user-message → deduplicated (skip if already shown)
5. Messages cached to localStorage
```

### Sending a message while offline

```
1. User submits text
2. Message added to timeline optimistically
3. ws/dispatch! detects WS is closed → queues as pending message
4. Pending message saved to localStorage
5. Pending indicator shown at bottom of timeline (spinner + count)
6. On reconnect:
   a. Server sends :waiting-for-join
   b. Client auto-joins room (new or existing)
   c. flush-pending! sends queued messages
   d. Server echoes confirm → pending entries removed
```

### Viewing a cached session offline

```
1. Home view shows cached sessions with "Offline" badge
2. "New Session" button hidden (can't create rooms offline)
3. Click a session → open-cached-session! loads messages from localStorage
4. Chat view renders cached messages (read-only)
5. User can type → message queued as pending
6. On reconnect → session resumed, pending messages flushed
```

### Reconnecting after disconnect

```
1. WS onclose fires → connected? = false, exponential backoff retry
2. WS reconnects → server sends :waiting-for-join
3. Client checks if it was in a chat:
   a. Session has active room → rejoin that room
   b. Session exists but no room → join-and-resume! (new room + /resume N)
   c. No session but pending messages → join new room, flush pending
   d. Not in chat → show home view
4. Server sends :history → replaces cached messages (backend wins)
```

## Source Files

```
src/xi/
  session/
    format.cljc       — shared data shapes, key construction, pending message records
  web/
    cache.cljs        — localStorage read/write (mirrors session.cljs path→JSON pattern)
    ws.cljs           — WebSocket transport with offline queueing and auto-rejoin
    state.cljs        — app-state atom (includes :pending-messages, :session-id)
    core.cljs         — entry point (hydrates from cache before WS connect)
    views.cljs        — UI rendering (inline status bubbles, offline indicators)
```

## Caveats

- **No service worker.** If the page itself can't load (full network down), the app won't start. Offline support covers "page loaded but WS backend is down."
- **localStorage limits.** Large sessions with many tool results may hit the ~5MB limit. Messages with base64 images are particularly heavy.
- **Session list is server-authoritative.** The cached list may be stale — sessions created from other clients won't appear until the next handshake.
