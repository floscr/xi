# Server & Rooms

Xi runs the same event-driven core in every mode ([architecture.md](architecture.md));
the server mode adds a WS transport and a room manager on top of it.

Commands, flags and environment variables: [cli.md](cli.md). One-shot
`xi prompt`: [prompt-mode.md](prompt-mode.md).

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
│  + POST /api/rooms (create a room over HTTP)    │
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

#### Bind address

The server listens on **all interfaces (`0.0.0.0`)**, so `localhost`,
`127.0.0.1`, a Tailscale address and the LAN all reach it. `--host` /
`XI_HOST` take a comma-separated list of addresses and only ever *add* to
that: loopback is always bound next to them.

```bash
XI_HOST=100.64.0.10 xi server --headless          # 127.0.0.1 + 100.64.0.10
xi server --headless --host 100.64.0.10,192.168.1.5
```

`--host` wins over `XI_HOST`; a blank value means unset. `0.0.0.0` (or `::`)
in the list already covers every address and stands alone. The setting applies
to both the plain and the TLS listener. The pairing handshake is what protects
a coding server once it is reachable (see [client-auth.md](client-auth.md)); an
agent server (`--agent`) does not pair, so only run it where the network is
trusted. The startup line says when the server is reachable from the network.

### Join / Create (`xi join`, `xi create`)

A TUI client over `xi.client.ws-transport` (forward + mirror — see
[architecture.md](architecture.md)). `join` targets `"latest"`, `create`
targets `"new"`. Exits with an error if the server isn't running (the TUI
does not opt into reconnect; the web client does).

### Agent server (`xi server --agent <id>`)

Runs Xi as a named agent — e.g. a conversational assistant with a handful of
search tools — instead of a coding agent. The agent's profile is the
`[:agents <id>]` entry of `~/.config/xi/config.edn` (see
[cli.md](cli.md#agent-profiles)); rooms provisioned by the server carry
`[:agent :agent-id]` and `[:agent :only-tools]`, which drive everything
downstream:

- The provider advertises only the profile's `:tools` (no file, edit, bash,
  … unless listed); the hidden tools are unreachable, not just undocumented.
- AGENTS.md, skills and extension prompts are replaced by the profile's
  system prompt. The profile is re-read for every new room, so a config edit
  applies to the next chat without a restart.
- Image attachments still work (processed in the prompt, not via tools).
- Sessions are stored separately in `~/.config/xi/personal-agent/<id>/`;
  the lobby, `/new`, and `/resume` list only those sessions, and the web
  client shows a flat session list with no projects.
- Rooms carry `[:ext :agent {:id "<id>"}]`, so rules can target the agent
  with `:when {:agent {:id "<id>"}}`.

```bash
xi server --headless --agent root   # start the root agent profile
xi join                              # connect from another terminal
```

## HTTP API

Besides serving the web client and upgrading WebSocket connections, the server
exposes a small HTTP API so external tooling can drive it. It is served on the
same port (7474) — and, when TLS is configured, on the TLS port too (the two
share one fetch handler).

### `POST /api/rooms` — create a room (spawn a background agent)

Provisions a fresh room (session + system prompt for the given cwd) and, when a
`prompt` is supplied, immediately kicks off an agent turn. The room runs
**clientless** — background agents are the whole point of a headless server, so
no client needs to be attached (see *Busy rooms keep running* below). The turn
auto-titles the session from the first message, and the session persists on
disk, so the returned `url` (`/chat/<session-id>`) resumes it in the web client
later.

A task tracker, for example, can POST a task's prompt + project cwd and get
back a URL to open.

**Auth** — same client-key trust as the WS transport: send an approved key (or
the local `~/.config/xi/client-key`) as either header:

```
Authorization: Bearer <client-key>
X-Xi-Client-Key: <client-key>
```

Auth is skipped on an agent server (`--agent`; single-user/local, like WS).

**Request body** (JSON, all fields optional):

| field    | meaning                                                        |
|----------|----------------------------------------------------------------|
| `prompt` | first user message; when present, starts a turn immediately     |
| `cwd`    | working directory the agent runs in (default: the server's cwd) |
| `model`  | model override (default: the server's configured model)         |

**Response** (`200`, JSON):

```json
{
  "room-id": "r-mte9cxsi-jaql",
  "session-id": "01a04d23-271b-7a48-a3dd-f25b97460a43",
  "cwd": "/home/user/Code/Projects/xi",
  "url": "http://localhost:7474/chat/01a04d23-271b-7a48-a3dd-f25b97460a43"
}
```

Status codes: `401` (bad/missing key), `404` (unknown `/api/*` path), `405`
(non-POST), `400` (unparseable body).

**Example**

```bash
curl -sX POST http://localhost:7474/api/rooms \
  -H "Authorization: Bearer $(cat ~/.config/xi/client-key)" \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"Review the open diff and suggest fixes","cwd":"/home/me/proj"}'
# → {"room-id":"…","session-id":"…","cwd":"/home/me/proj","url":"http://localhost:7474/chat/…"}
```

Open the returned `url` in the web client to watch (or continue) the agent.

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
   the same pure reducers. A join may carry `:cached-history-hash` +
   `:cached-history-count` (the web client's cached history fingerprint);
   when they match a prefix of the live room's history, the snapshot omits
   `:history` and sends `:history-base {:hash :count}` + `:history-tail`
   instead, and the client splices its cache back in
   ([web-offline.md](web-offline.md#live-room-transfer-skip-history-base--history-tail)).
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

## Crash resilience

The server is a long-lived, multi-client process, so a single stray async
error must not take everyone down. `install-crash-guard!` (in `xi.cli`, run
at the start of `start-server!`) installs process-level handlers for
`unhandledRejection` and `uncaughtException`:

- **It keeps the server alive.** Without it, one unhandled rejection — most
  often an `EPIPE` from a Claude SDK / sub-agent subprocess pipe on teardown
  — crashes the whole process and drops every connected client (they see
  "connecting to server"). The guard logs the error instead of exiting.
- **It logs loudly, not silently.** Every caught error is written with its
  full stack to **stderr** *and appended to `~/.config/xi/crash.log`*. The
  guard is a net, not a fix: root causes stay findable so the actual
  escaping path can be repaired at the source.

The file copy matters because `bb serve:restart` respawns the `xi-serve`
tmux pane and wipes its scrollback — so a crash printed only to the pane is
lost on restart. `~/.config/xi/crash.log` survives, so after a crash:

```bash
cat ~/.config/xi/crash.log   # timestamped label + stack for each caught error
```

> The guard is server-only (it's installed in `start-server!`, not in
> standalone/client/prompt modes). Standalone and one-shot `xi prompt` runs
> are short-lived and single-user, so a crash there is surfaced directly
> rather than swallowed.

## Source files

```
src/xi/
  cli.cljs                 — entry point, subcommand routing, per-mode assembly
  wire.cljs                — transit (JSON) encode/decode of event maps
  auth.cljs                — client-key auth store (see client-auth.md)
  server/
    ws.cljs                — Bun WS server, broadcast tap, static file serving
    room_manager.cljs      — rooms as pure event handlers (join/attach/cleanup)
  client/
    ws_transport.cljs      — client transport (forward + mirror, reconnect opt-in)
```
