# Web Client

Browser-based client for xi. Connects to the WS server via WebSocket, renders a chat interface with [Replicant](https://github.com/cjohansen/replicant), and supports offline usage via localStorage caching.

## Quick Start

Start the server, then open `http://localhost:7474` in a browser:

```bash
xi server --headless        # or `xi server` for headless + TUI
```

The client auto-connects and shows the home view with available sessions.

## Features

### Multi-session Home View

The home screen lists all saved sessions with real-time status:

- **Active indicator** — green dot for sessions with a live room
- **Busy spinner** — animated spinner when the agent is working
- **Unread dot** — accent-colored dot when new responses arrived since you last viewed
- **Relative timestamps** — "5m ago", "2h ago", "3d ago"
- **Offline badge** — shown when disconnected from server

Click a session to rejoin its active room (if running) or resume it in a new room.

### Streaming Chat

Messages stream in real-time as the agent works:

- **Text deltas** — assistant responses appear character-by-character
- **Thinking blocks** — collapsible extended thinking display
- **Tool calls** — collapsible blocks showing tool name, arguments, and result
- **Image attachments** — paste or attach images, shown as thumbnails with lightbox
- **Markdown rendering** — assistant text rendered as formatted markdown
- **Auto-scroll** — timeline stays pinned to bottom unless you scroll up

### Session Management

- **New session** — (+) button creates a fresh room
- **Resume** — click any saved session to resume it
- **Leave** — back arrow returns to home (agent keeps running in background)
- **Switch** — (+) from chat creates a new session without stopping the current one
- **Load** — dropdown button opens the resume picker overlay

### Unread Indicators

Client-only tracking for new agent responses:

1. When you send a message, the current assistant response count is stored in localStorage
2. On reconnect, the client asks the server for current response counts
3. Sessions where the server count exceeds the stored count show an unread dot
4. Clicking a session or viewing a response clears the indicator

### Offline Support

Full offline support — see [web-offline.md](web-offline.md) for architecture details.

- **Instant startup** — UI hydrates from localStorage cache before WS connects
- **Cached sessions** — browse and read sessions while offline
- **Pending messages** — messages sent offline queue and flush on reconnect
- **Auto-reconnect** — exponential backoff reconnection (1s → 30s max)

### Image Attachments

- **Paste** — Ctrl+V / Cmd+V images from clipboard
- **File picker** — camera/gallery button for file upload
- **Preview strip** — thumbnails shown before send, removable
- **Lightbox** — click any image (sent or received) for full-size view

### URL Routing

History-based routing with shareable URLs:

| Path | View |
|------|------|
| `/` | Home (session list) |
| `/chat/:session-id` | Chat for a specific session |

Browser back/forward navigation works correctly. Refreshing a chat URL hydrates from cache then reconnects.

### Keyboard Shortcuts

| Key | Action |
|-----|--------|
| `Enter` | Send message |
| `Shift+Enter` | Newline in compose |
| `Ctrl+V` / `Cmd+V` | Paste image from clipboard |

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
│       │    ┌─────────┼──────────┐                     │
│       │    │ router.cljs        │                     │
│       │    │ (History API)      │                     │
│       │    └────────────────────┘                     │
│       │              │                                │
│       │         ┌────┴─────┐                          │
│       │         │cache.cljs│                          │
│       │         └────┬─────┘                          │
│       │              │                                │
│       │         ┌────┴──────────┐                     │
│       │         │  localStorage │                     │
│       │         └───────────────┘                     │
│  ┌────┴─────┐                                         │
│  │ views.cljs│  (Replicant rendering)                 │
│  └──────────┘                                         │
└─────────────────────────────────────────────────────┘
```

### Data Flow

1. **Render loop** — `app-state` atom triggers re-render on every change
2. **Events in** — WebSocket messages parsed and applied to `app-state`
3. **Commands out** — user actions dispatched as JSON messages over WS
4. **Cache layer** — reads/writes localStorage alongside state mutations
5. **Router** — syncs URL ↔ app-state `:route`, handles popstate

### State Shape

```clojure
{:route            {:page :home|:chat, :session-id "..."}
 :rooms            [...]           ;; live rooms from server
 :home-sessions    [...]           ;; saved session list
 :active-sessions  #{"sid" ...}    ;; sessions with live rooms
 :watched-sessions {"sid" count}   ;; unread tracking
 :response-counts  {"sid" count}   ;; from server query
 :room-id          "room-123"      ;; joined room
 :session-id       "sess-abc"      ;; current session
 :connected?       true/false
 :personal-agent?  true/false
 :busy?            true/false
 :messages         [{:type :user/:assistant/:tool/:thinking/:status ...}]
 :pending-messages [{:id :payload :timestamp :status}]
 :compose-text     ""
 :compose-images   [{:data :media-type :preview-url}]
 :model            "claude-..."
 :collapsed-blocks #{"tool-0" ...}
 :resume-sessions  nil|[...]
 :lightbox-image   nil|"data:..."}
```

## WebSocket Protocol

All messages are JSON. The client and server exchange typed messages:

### Client → Server

| Type | Fields | Description |
|------|--------|-------------|
| `join` | `room`: "new"\|"latest"\|room-id | Join/create a room |
| `leave` | — | Leave current room, return to lobby |
| `prompt` | `text`, `images?` | Send user message |
| `command` | `name`, `args` | Run a slash command (e.g. "resume") |
| `abort` | — | Interrupt the agent |
| `query-response-counts` | `sessions`: [sid...] | Ask for response counts (lobby only) |

### Server → Client

| Type | Fields | Description |
|------|--------|-------------|
| `waiting-for-join` | `rooms`, `sessions`, `active-sessions` | Lobby handshake |
| `room-joined` | `room-id` | Confirm room entry |
| `rooms-updated` | `rooms`, `active-sessions`, `sessions?` | Live lobby push |
| `ready` | `model`, `cwd` | Room ready state |
| `user-message` | `text`, `images?` | Echo of user message |
| `text-delta` | `text` | Streaming assistant text |
| `thinking` | `text` | Extended thinking content |
| `tool-start` | `name`, `arguments` | Tool invocation began |
| `tool-args` | `name`, `arguments` | Updated tool arguments |
| `tool-result` | `content`, `is-error` | Tool completed |
| `turn-start` | — | Agent turn began |
| `turn-end` | `session-id?` | Agent turn completed |
| `busy-changed` | `busy` | Agent busy state changed |
| `error` | `error`\|`text` | Error occurred |
| `aborted` | — | Agent interrupted |
| `history` | `events` | Full message history replay |
| `session-resumed` | `messages`, `session` | Resumed session data |
| `session-cleared` | — | Session was cleared |
| `session-compacted` | — | Session was compacted |
| `compact-start` | — | Compaction starting |
| `command-result` | `command`, `text?`, `sessions?` | Command output |
| `command-error` | `text` | Command failed |
| `response-counts` | `counts`: {sid: n} | Response counts for unread |
| `quit` | — | Server shutting down room |

## Source Files

```
src/xi/web/
  core.cljs     — entry point (render loop, viewport handling, WS init)
  state.cljs    — app-state atom definition
  views.cljs    — Replicant view functions (home, chat, compose, lightbox)
  ws.cljs       — WebSocket transport (connect, dispatch, offline queue, unread)
  cache.cljs    — localStorage persistence (sessions, messages, pending, watched)
  router.cljs   — History API routing (navigate, replace, popstate)

src/xi/web/ (UI components)
  markdown/hiccup.cljs — markdown → hiccup rendering

resources/public/
  css/style.css — all styles
  index.html    — shell HTML (loads compiled JS)
```

## localStorage Keys

| Key | Contents |
|-----|----------|
| `xi/sessions-list` | Array of session summaries |
| `xi/messages/{session-id}` | Message array for a session |
| `xi/pending-messages` | Offline send queue |
| `xi/last-room` | `{room-id, session-id}` for reconnect |
| `xi/watched-sessions` | `{session-id: response-count}` for unread tracking |

## Mobile Support

- **Viewport handling** — uses `visualViewport` API to handle iOS keyboard resize
- **Touch-friendly** — large tap targets, no hover-dependent interactions
- **PWA-ready** — works as home screen app (no service worker yet)
