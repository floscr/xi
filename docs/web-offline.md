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
| `xi/room/<session-id>` | last `{:history :model :msg-hash :msg-count :history-hash}` per session for chat paint |
| `xi/room-lru` | `[sid …]` most-recent-first, caps the room snapshots |
| `xi/watched` | `{session-id response-count-when-last-seen}` (unread) |

### Keeping the store under quota

localStorage is ~5MB per origin, and both the lobby and the per-room
snapshots grow without bound (thousands of saved sessions, heavy tool
results). Once a single `setItem` throws `QuotaExceededError`, **every**
subsequent write fails-safe (warn + continue) and the cache silently stops
updating. Two guards keep it small:

- **Lobby trim.** `save-lobby!` stores only favorites + the
  most-recently-accessed N sessions (`max-cached-sessions`, 80) — a first
  paint doesn't need the full history, and the live `:lobby/state`
  restores it. This is the big one: the untrimmed list was hundreds of KB.
- **Room LRU.** `save-room!` keeps at most `max-cached-rooms` (15) room
  snapshots, pruning the least-recently-saved (and any legacy keys) on
  every write. On a `QuotaExceededError` it evicts the other rooms
  **oldest first, one at a time**, retrying after each, so the most
  recently opened chats survive (the snapshot is encoded once, not per
  retry).
- **Oversized rooms.** A snapshot longer than `max-room-chars` (5 Mi
  UTF-16 chars, more than any browser's whole quota) is skipped without
  evicting anything. One that still doesn't fit with every other room gone
  is recorded in `too-big` (by history length), so later persists skip it
  instead of re-encoding megabytes on every `:lobby/state` only to fail
  again. A shorter history (`/compact`) gets another try.

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

### Cache seed on in-app navigation

`hydrate` only seeds `:web/cache` for the URL the page *loaded* on. When
you tap another chat inside the app, `router/navigate` also emits a
`:cache/seed-room` effect that reads that session's cached snapshot into
`:web/cache`, so the chat view paints its history immediately while the WS
`:room/joined` round-trips (noticeable on slow mobile links). `:room/joined`
then overwrites it with the live room.

Decoding a cached room (transit, with tool results) is the expensive part of
the tap, and a switch reads the snapshot twice (`:cache/seed-room` and
`:room/join-with-cache`). `cache/load-room` therefore memoizes the last decoded
room, keyed on the raw localStorage string (a `save-room!` in between can't
serve stale data). Session cards also dispatch `:cache/prefetch` on
`pointerdown`, which warms that memo while the finger is still down, so by the
time `click` fires the decode is already done. The timeline's virtualized
window and the join/hash handshake are unchanged.

The persist tap fires on every `:lobby/state` / `:room/joined`, so after a
switch it used to re-encode and rewrite the whole room (and the lobby) it had
just loaded. `save-room!` now skips when the history is the *identical* vector
already stored (the cache-promoted history is the decoded object itself, so the
check is O(1)), and `save-lobby!` skips an identical lobby map. Real changes
(appends, resumes) produce new values and still write.

### Painting cache while the server history resumes

Opening an idle session goes through a disk **resume**: the server installs
the room with an *empty* history for a beat, then streams the messages in a
separate `:session/resumed`. The chat view must not let that empty history
shadow the cache — `(or (:history room) (:history cached))` returns `[]`
(truthy) and blanks the timeline, which used to flash the “Connecting…”
spinner between the cached paint and the newest chat. Instead `chat-view`
prefers the room history only once it is *authoritative* (`(seq room-history)`)
and otherwise falls through to the cache:

- **cache present** — paint it immediately and show a quiet `Updating…` hint
  in the chat topbar (`.topbar-updating`) while the resume is in flight; the
  hint clears the moment the authoritative history lands (identical history =
  no visible change; new messages simply reconcile in). No spinner flash.
- **no cache** — fall back to the blocking `Connecting…` spinner, since there
  is nothing to paint.

The live re-attach path (`:room/joined` carries the full history, or the
cached one spliced back in, see below) is authoritative on arrival, so it
never shows the `Updating…` hint.

### Live-room transfer skip (`:history-base` / `:history-tail`)

The `:session/current` skip below only covers the **disk resume**. A session
whose room is still live (another client attached, a turn running, an
orphaned busy room) is joined by `:room/attach`, which used to send the whole
room snapshot, history and all: re-opening a long chat re-downloaded and
re-decoded megabytes it already had cached (2.9 MB per switch for an 11 MB
transcript).

The live path now uses a fingerprint of the rendered history itself:

1. **Cache it.** `save-room!` stores `:history-hash` (`(hash history)`)
   with each snapshot.
2. **Echo it.** `:room/join-with-cache` adds `:cached-history-hash` and
   `:cached-history-count` to the join; `room-join` forwards them on the
   live room's `:room/attach`.
3. **Elide.** `room-manager/joined-payload` hashes the room's first
   `count` history entries. On a match the `:room/joined` carries the room
   **without** `:history`, plus `:history-base {:hash :count}` and the
   newer entries as `:history-tail` (empty when nothing changed).
4. **Splice.** The web client's `:room/joined` handler
   (`room-joined-from-cache`) checks the base against its `:web/cache`
   snapshot and installs `cached-history ++ tail`. With an empty tail it
   installs the cached vector itself, so the persist tap's identity check
   skips the rewrite too. If the cache no longer matches, it paints what it
   has and re-joins without the fingerprint to get a full snapshot.

A rewritten history, or an entry still streaming since the snapshot, fails
the hash and falls back to the full snapshot. Clients that send no
fingerprint (the TUI, reconnect replays) always get the full room. Observed:
re-opening the 11 MB session's live room dropped from a 2.9 MB `:room/joined`
to 10.5 KB, with no long tasks on the main thread.

### Hash-validated transfer skip (`:session/current`)

Resuming a large session ships its whole transcript over the wire — the
expensive part on a slow mobile link. When the client already has that exact
transcript cached there is no reason to resend it. Xi detects this with a
content hash and skips the transfer.

Because client and server are the **same ClojureScript** compiled to JS,
`(hash messages)` of equal EDN values matches across the two processes. That
makes the hash a cheap, reliable “are we already current?” check:

1. **Cache the hash.** Every `:session/resumed` now carries `:msg-hash`
   (`(hash raw-messages)`, computed server-side). The web cache stores it
   alongside the room's `:history`/`:model` under `xi/room/<sid>`, and
   `:session/resumed` is in `persist-on` so even a cold-opened chat gets its
   hash cached.
2. **Echo it on join.** Router chat-joins dispatch through the
   `:room/join-with-cache` effect, which reads the cached `:msg-hash` and
   attaches it to the `:room/join` as `:cached-msg-hash`. The server threads
   it through `:room/setup`.
3. **Compare + skip.** In the `:room/setup` fx the server recomputes
   `(hash messages)` from disk and compares. On a match it:
   - broadcasts the `:session/resumed` with `:no-broadcast? true` so the
     server still fills **its own** room mirror (multi-client correctness)
     without pushing the full history to the joining client, and
   - sends a tiny `:session/current {:session-id :msg-hash}` instead.
   The client's `:session/current` handler repaints `[:rooms room-id :history]`
   from its own cache and stamps the room's `:msg-hash`.

A mismatch (or a client with no cached hash) always falls back to the normal
full `:session/resumed` — a hash collision is astronomically unlikely, and any
divergence is a safe over-send, never wrong data. The broadcast tap honours a
per-event `:no-broadcast?` flag in addition to the static no-broadcast set.

Wire effect: an unchanged large session drops from a ~19 KB `:session/resumed`
to a ~185 B `:session/current` — the client paints entirely from cache.

### Truncating tool output on the resume wire

Every surface caps how much of a tool result it renders — the web client
hard-clips each result at 100 lines (`xi.web.views`, with a `… (N more lines)`
marker) and the TUI at `truncate-output-block-after-n-lines` (default 100). So
shipping a resumed transcript's *full* tool output is pure waste: a single
`take_snapshot` a11y tree or `bash`/`grep` dump can be thousands of lines the
client never shows.

On the resume path the server therefore clips each `:tool-result` block's text
to `session/resume-result-line-cap` (100) via `session/truncate-message-results`
before the messages enter `:session/resumed`
(`xi.session/truncate-message-results` + `xi.util/truncate-text-lines`). The
clip is idempotent — it yields at most `n` lines including the marker, so the
client's own re-truncation at the same cap is a no-op. `read-session-messages`
already flattens tool-result content to a string (images never survive the
resume path), so only strings are touched.

The transfer-skip hash is taken over the **truncated** wire form, so it tracks
exactly what the client caches and renders: change the cap and the hash shifts,
busting stale caches into a fresh resend. Observed: a resume dominated by one
204-line diff dropped from ~19 KB to ~8.9 KB.

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
  approach the quota (Chromium ~5M chars, Safari about half); writes fail
  safe (warn + continue). The lobby trim, room LRU and oversized-room skip
  (see "Keeping the store under quota") bound the total so the cache
  doesn't overflow and stall. A session too big to cache paints from the
  server every time.
- **Lobby may be stale offline.** Sessions created elsewhere appear on the
  next `:lobby/state`.
