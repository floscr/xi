# Server & Rooms

Xi runs the same event-driven core in every mode ([architecture.md](architecture.md));
the server mode adds a WS transport and a room manager on top of it.

## CLI Commands

```
xi              Standalone TUI. One local room, connected to nothing.
xi server       Start a WS server + a local TUI client in the same process.
xi join [url]   Connect a TUI client to the latest room on a running server.
xi create [url] Connect a TUI client to a new room on a running server.
```

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
  echoed (as EDN, `xi.wire`) to that room's clients — sender included.
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
serialized as EDN strings (`xi.wire/encode` / `decode`). Both peers are
ClojureScript, so keywords and nesting survive. `:remote?` is
transport-local and never sent.

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
  while the agent is idle, or when a turn ends with no clients attached.

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
