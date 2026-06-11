# Server & Multi-Session Architecture

Xi supports two modes of operation: **standalone** and **server**.

## CLI Commands

```
xi              Standalone TUI + runtime. No server, no WebSocket.
xi server       Start a WS server, create a session, attach TUI.
xi join         Connect TUI to the latest session on a running server.
xi create       Connect TUI to a new session on a running server.
xi sessions     List active sessions on a running server (print & exit).
```

### Flags

```
--port N                Override the default port (7474). Applies to all server commands.
--headless              Server only: run without a local TUI. Clients attach remotely.
--personal-agent-only   Server only: run as a personal assistant with no coding tools.
```

### Environment

- `XI_PORT` — default port when `--port` is not specified (fallback: 7474)

## Modes

### Standalone (`xi`)

Creates a runtime and TUI in the same process, wired directly — no networking.
This is the simplest mode: one runtime, one TUI, no server.

```
┌─────────────────────┐
│  Process             │
│                      │
│  Runtime ←→ TUI      │
│  (dispatch!/events)  │
└─────────────────────┘
```

The TUI transport calls `runtime/dispatch!` directly and checks `runtime/busy?` for state.
No WebSocket server is started. Useful for quick single-user sessions.

### Server (`xi server`)

Starts a WebSocket server with a session manager, creates an initial session,
and attaches a local TUI to it.

```
┌──────────────────────────────────────────┐
│  Server Process                           │
│                                           │
│  ┌─────────────────┐                      │
│  │ Session Manager  │   WS Server (:7474) │
│  │                  │        ↑             │
│  │  Session A ←→ TUI│        │             │
│  │  Session B       │←───────┘             │
│  │  ...             │   remote clients     │
│  └─────────────────┘                      │
└──────────────────────────────────────────┘
```

The local TUI connects to the session directly (same transport as standalone).
Remote clients connect over WebSocket and go through the join handshake.

#### Headless (`xi server --headless`)

Same as above but no local TUI is created. The server runs in the background
waiting for remote clients.

```bash
xi server --headless           # start
xi join                        # from another terminal
xi create                      # or start a new session
```

### Join (`xi join`)

Connects a TUI to the **latest** session on a running server via WebSocket.
If the server has multiple sessions, you get the most recently created one.
Exits with an error if the server is not running.

### Create (`xi create`)

Connects a TUI to a **new** session on a running server via WebSocket.
The server creates a fresh runtime and the client attaches to it.

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

The home screen shows personal-agent sessions separately from coding sessions.

### Sessions (`xi sessions`)

Queries a running server for its active sessions and prints them:

```
Active sessions:
  s-abc123-def  — 2 client(s) (created Fri May 08 2026 ...)
  s-xyz789-ghi  — 0 client(s) (created Fri May 08 2026 ...)
```

Connects briefly over WebSocket, reads the session list from the server handshake,
then disconnects and exits.

## WebSocket Protocol

Default port: **7474** (`ws://localhost:7474`).

### Handshake

1. Client connects via WebSocket.
2. Server sends `waiting-for-join` with the current session list:
   ```json
   {"type": "waiting-for-join", "sessions": [{"id": "s-...", "clients": 1, "created": 1715...}]}
   ```
3. Client sends a `join` message:
   ```json
   {"type": "join", "session": "latest"}
   ```
   `session` can be `"latest"`, `"new"`, or an explicit session ID.
4. Server responds with confirmation:
   ```json
   {"type": "session-joined", "session-id": "s-abc123-def"}
   ```
5. Normal event/command flow begins.

### Message Flow (after join)

**Server → Client** (events): same structured maps as the runtime event bus.

```json
{"type": "turn-start"}
{"type": "text-delta", "text": "partial..."}
{"type": "tool-start", "id": "toolu_abc", "name": "bash", "arguments": {"command": "ls"}}
{"type": "tool-result", "id": "toolu_abc", "content": [...], "is-error": false}
{"type": "turn-end", "session-id": "...", "usage": {...}, "cost": 0.03}
{"type": "busy-changed", "busy": true}
```

**Client → Server** (commands): parsed by the runtime's command system.

```json
{"type": "prompt", "text": "refactor the auth module"}
{"type": "command", "name": "clear"}
{"type": "abort"}
```

## Session Lifecycle

- Sessions are created by `xi server` (initial session), `xi create`, or when a client joins and no session exists yet.
- Each session is an independent runtime with its own agent loop, event bus, and state.
- Multiple clients can connect to the same session — they all see the same events.
- When the last client disconnects from a session, the session is destroyed.

## Source Files

```
src/xi/
  cli.cljs                    — entry point, subcommand routing
  server/
    ws.cljs                   — WS server (Bun.serve, handshake, message routing)
    session_manager.cljs      — session CRUD (create, join, list, client tracking)
  client/
    ws_transport.cljs         — WS client transport (join handshake, event forwarding)
    tui.cljs                  — TUI client (events → terminal rendering)
```
