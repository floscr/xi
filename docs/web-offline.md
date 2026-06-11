# Web Client Offline Support

The web client works without a live WebSocket connection. The lobby and
per-session history are cached in `localStorage` so they're available
immediately on page load, even when the backend is down.

## Design principles

- **Backend wins.** The cache only fills the gap before the WS connects
  (and while offline). `:room/joined` and `:lobby/state` overwrite it.
- **Instant startup.** State is hydrated from cache *before* the socket
  opens — home paints from the cached lobby, a deep-linked chat paints
  from the cached history.
- **EDN, not JSON.** Values are stored with `pr-str`/`read-string` — the
  same format as the wire — so keywords and nesting survive without shims.

## Storage layout (`xi.web.cache`)

| Key | Contents |
|---|---|
| `xi/lobby` | last `{:rooms :sessions}` for an instant home paint |
| `xi/room/<session-id>` | last `{:history :model}` per session for chat paint |
| `xi/watched` | `{session-id response-count-when-last-seen}` (unread) |

## Hydrate

On boot, `cache/hydrate` seeds the initial app state from the cache before
`create-app` starts:

- `:web/route` — parsed from the URL
- `:lobby` — cached lobby, if any
- `:web/cache {sid {:history :model}}` — the deep-linked session's cached
  history (the chat view falls back to this while the room rejoins)
- `:web/watched` — the unread watermark map

## Persist

A single **app tap** (`cache/persist-tap`) mirrors the lobby and the
active room into localStorage. It is gated to a whitelist of event types
so streaming doesn't thrash storage:

```clojure
#{:lobby/state :room/joined :agent/turn-end :agent/tool-result :agent/abort}
```

`:history/append`-style per-delta writes are intentionally excluded;
`:agent/tool-result` gives a mid-turn checkpoint and `:agent/turn-end`
persists the final state.

## Reconnect (`xi.client.ws-transport`)

The web client opts into `:reconnect?`:

- exponential backoff, 1s → 30s max
- an in-memory **pending-send queue** — events dispatched while the socket
  is closed are flushed on reopen
- the last `:room/join` is remembered (per join, cleared on `:room/leave`)
  and **replayed on reopen**, so a dropped connection restores the room
- `:connection/status` events drive the offline badge
  (`:web/connected?`)

The TUI client does *not* opt in — it keeps exit-on-disconnect.

## Flows

### Page load (online)

```
hydrate (cache)  →  instant paint
ws connects      →  :lobby/state / :room/joined replace cached state
```

### Page load (backend down)

```
hydrate (cache)  →  home shows cached sessions + offline badge
                    deep-linked chat renders cached history read-only
ws retries with backoff; on success the normal flows resume
```

### Sending while a room isn't joined yet

Submitting from a cached chat view stashes the message as
`:web/pending-submit`; a tap fires it through the normal `:input/submit`
path once `:room/joined` for that session arrives (guarded against
session-id mismatch so a navigation race can't send into the wrong room).

## Caveats

- **No service worker.** If the page itself can't load, the app won't
  start. Offline support covers "page loaded but WS backend is down."
- **localStorage limits.** Long sessions with heavy tool results can
  approach the ~5MB quota; writes fail safe (warn + continue).
- **Lobby may be stale offline.** Sessions created elsewhere appear on the
  next `:lobby/state`.
