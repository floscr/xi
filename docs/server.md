# Server & Rooms

Xi runs the same event-driven core in every mode ([architecture.md](architecture.md));
the server mode adds a WS transport and a room manager on top of it.

## CLI Commands

```
xi              Standalone TUI. One local room, connected to nothing.
xi server       Start a WS server + a local TUI client in the same process.
xi prompt <txt> One-shot: run a single prompt headless, print the response, exit.
xi join [url]   Connect a TUI client to the latest room on a running server.
xi create [url] Connect a TUI client to a new room on a running server.
```

`xi prompt` (aka `xi -p`) is a headless, non-interactive mode for scripting and
piping — see [prompt-mode.md](prompt-mode.md).

### Flags

```
--port N                Override the default port (7474). Applies to all server commands.
--headless              Server only: run without a local TUI. Clients attach remotely.
--personal-agent-only   Server only: run as a personal assistant with no coding tools.
--model NAME            Override the default model.
--debug-events          Write the full event stream as JSONL (see architecture.md).
```

### Environment

- `XI_PORT` — default port when `--port` is not specified (fallback: 7474)

## Modes

### Standalone (`xi`)

One process, one app, one room — the standard assembly with a local TUI
renderer. No networking. Standalone is not a special case: it's the same
state shape and code paths as server/client, just "not connected".

### Server (`xi server`)

```
┌────────────────────────────────────────────────┐
│  Server Process                                 │
│                                                 │
│  one app atom                                   │
│  ├── :rooms {room-A {...} room-B {...}}         │
│  └── :connection :clients {cid {:room-id …}}    │
│                                                 │
│  WS server (:7474)  ── broadcast tap            │
│   ├── remote TUI / web clients                  │
│   └── local TUI client (non-headless; joins     │
│       via WS like any remote client)            │
│                                                 │
│  HTTP (:7474) — serves the web client from      │
│  resources/public (SPA fallback to index.html)  │
└────────────────────────────────────────────────┘
```

- Rooms live in the **same single app atom** under `:rooms` — each has its
  own history, session, agent state, and UI state.
- **Broadcast is a tap**: every processed event carrying a `:room-id` is
  echoed (transit-serialized, `xi.wire`) to that room's clients — sender included.
  Clients never apply their own input locally; they mirror the server's
  event order.
- Roomless (lobby) clients receive `:lobby/state` refreshes — live rooms
  plus the saved sessions list (read lazily, only when lobby clients
  exist).
- Incoming room events get `:room-id` forced to the sender's joined room.

#### Headless (`xi server --headless`)

Same, minus the local TUI client.

```bash
xi server --headless           # start
xi join                        # from another terminal
# or open http://localhost:7474 in a browser
```

### Join / Create (`xi join`, `xi create`)

A TUI client over `xi.client.ws-transport` (forward + mirror — see
[architecture.md](architecture.md)). `join` targets `"latest"`, `create`
targets `"new"`. Exits with an error if the server isn't running (the TUI
does not opt into reconnect; the web client does).

### Personal Agent (`xi server --personal-agent-only`)

Runs Xi as a conversational personal assistant with no coding tools. Rooms
provisioned by the server carry `[:agent :personal-agent?]`, which drives
everything downstream:

- The provider restricts the MCP tool bridge to `web_search` only (no file,
  edit, bash, etc.)
- AGENTS.md is replaced with a conversational system prompt
  (`system-prompt/PERSONAL_AGENT_PROMPT`)
- Image attachments still work (processed in the prompt, not via tools)
- Sessions are stored separately in `~/.config/xi/personal-agent/root/`;
  the lobby, `/new`, and `/resume` list only those sessions

```bash
xi server --headless --personal-agent-only   # start personal assistant
xi join                                       # connect from another terminal
```

## Wire protocol

There is no separate message schema — **the protocol is the event maps**,
serialized as transit strings (`xi.wire/encode` / `decode`). Both peers are
ClojureScript, so keywords and nesting survive. `:remote?` is
transport-local and never sent.

### Auth handshake

Before anything else, a client must authenticate with its client key —
`{:type :auth/hello …}` → `:auth/ok` / `:auth/pending` / `:auth/denied`.
Unknown clients are parked with a 4-digit pairing code until approved (web
banner or `bb serve:approve <code>`). See [client-auth.md](client-auth.md).

### Join flow

1. Client connects and sends `{:type :room/join :target …}` where target is
   `"latest"`, `"new"`, an explicit room id, or `{:session-id sid}` (resume
   a saved session into a new room).
2. The room manager resolves it to `:room/attach` (existing room) or
   provisions a room first (session + AGENTS.md), then attaches.
3. The server replies with `:room/joined` carrying a **room snapshot** —
   the client installs it and mirrors all subsequent broadcasts through
   the same pure reducers.
4. Roomless clients get `:lobby/state` instead.

After joining, everything is ordinary events both ways: `:input/submit`,
`:prompt/submit`, `:command/run`, `:agent/text-delta`, `:agent/turn-end`,
`:ui/dialog-open`, …

### Other connection-level events

- `:room/leave` — back to the lobby
- `:room/list` — request a `:lobby/state` refresh
- `:session/counts` → `:session/counts-result` — unread tracking (web)
- `:client/update {:visible? bool}` — per-client visibility (suppresses
  notifications while a visible client is attached; never broadcast)

## Room lifecycle

- Rooms are created on demand by `:room/join` targets (`"new"`, a
  session-id resume, or the first join on a fresh server).
- Multiple clients can attach to one room — they all mirror the same
  events.
- **Auto-destroy**: a room closes when its last client leaves/disconnects
  while the agent is **idle**, or when a turn ends with no clients attached.

### Busy rooms keep running — never abort on disconnect

A room with a **running** (`:agent :busy?`) turn is **never** closed or
aborted when its last client leaves or disconnects. Background agents are
the entire point of a headless server, so a busy orphaned room keeps
running; `turn-end-room-cleanup` reaps it the moment its turn ends with no
clients attached. That is the single reaping path for busy rooms.

> ⚠️ **Recurring pitfall — do not reintroduce "reap stale busy rooms".**
>
> It is tempting to abort the agent in a busy room once its last client
> leaves, to avoid "stale" rooms. This is wrong and has bitten this
> codebase repeatedly (`50ac3fe` → `a74a7b5` → `cb88424` → `c5141fa` →
> `cd027c2`). Every variant kills legitimately-running agents and surfaces
> as *"agents get aborted even though they're running."*
>
> **Why it keeps coming back:** a client disconnect is indistinguishable
> from "the user navigated to another chat." You cannot tell them apart, so
> any abort-on-disconnect (even behind a timeout) eventually kills a live
> agent.
>
> **The iOS/Safari trigger:** iOS Safari drops the WebSocket when you
> navigate (e.g. opening a chat from the sidebar). The server sees
> `:client/disconnect` on the busy room you navigated *away* from. A
> *"grace period before abort"* does **not** help: it only no-ops if a
> client reconnects to the **same** room, but navigation reconnects you to a
> **different** room, so the original busy room is found clientless and
> aborted. Chrome SPA navigation keeps the socket open, so the bug is
> invisible on desktop — making it look iOS-specific when it is really *any*
> real disconnect.
>
> **The rule:** `room-leave` and `client-disconnect-cleanup` may close a
> room only when it is **idle** and has no clients. Never schedule a
> delayed `:agent/abort` / orphan-check, and never add a timeout to decide
> whether the user "really left." Leaking a rare genuinely-hung SDK turn is
> far cheaper than aborting every running agent on every navigation (and an
> abort may not even resolve a hung SDK).

## Source files

```
src/xi/
  cli.cljs                 — entry point, subcommand routing, per-mode assembly
  wire.cljs                — EDN encode/decode
  server/
    ws.cljs                — Bun WS server, broadcast tap, static file serving
    room_manager.cljs      — rooms as pure event handlers (join/attach/cleanup)
  client/
    ws_transport.cljs      — client transport (forward + mirror, reconnect opt-in)
```
