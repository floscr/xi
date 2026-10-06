# Server mode

One Xi server holds many chats, and any number of terminals and browsers join
them. Run it on your workstation to use your phone, or on a box that is always
on.

## Starting it

```sh
xi server              # server + a terminal client in the same process
xi server --headless   # server only; join from elsewhere
```

The server listens on port 7474 (`--port` or `XI_PORT` change it) and on
every network interface, so other devices on your LAN or your Tailscale
network can reach it. Loopback is always included.

```sh
xi server --headless --host 100.64.0.10    # also bind one specific address
```

A terminal joins with:

```sh
xi join              # the most recent chat
xi create            # a new chat
xi join host:7474    # a server elsewhere
```

A plain `xi` in a directory joins a running local server on its own; pass
`--no-auto-join` to stay standalone.

## Pairing

Every client proves itself with a key. The terminal on the server's machine
is trusted automatically. A new browser gets a four-digit code and waits
until it is approved:

```sh
xi clients pending          # code, device name
xi clients approve 1234
xi clients                  # approved devices
xi clients revoke iPhone    # by name or key prefix
```

An already-paired browser shows a banner for the request and can approve it
too. The server picks approvals up within two seconds. Revoking a device
applies to its next connection.

A browser page from another site can never reach the server: the key is
required before any message is processed, and the server refuses connections
from other origins. The device name and platform shown with a code are what
the device claims; the code is the check.

| File | What |
| --- | --- |
| `~/.config/xi/client-key` | The key of the terminal on this machine, created on first run and trusted as is |
| `~/.config/xi/clients.edn` | Approved devices, each with an optional `:user` assignment |
| `~/.config/xi/pending-clients.edn` | Requests waiting for approval |

The web client keeps its key in the browser's storage, so each browser pairs
once. A server started with `--agent` does not pair at all.

## Users

Several people can share one server. Every connection belongs to a **user**,
a short id such as `root`, `alice` or `team-ops`. Xi uses it to tell people
apart: a prompt someone else sent shows their id above it, and the chat's
top bar lists who else is in the room. Nothing is verified. Pairing decides
whether a device may connect at all; the user id only says who it is.

The default user is `root`. A terminal picks another with `--user`:

```sh
xi join --user alice               # this terminal acts as alice
XI_USER=alice xi                   # the same, from the environment
```

A paired browser or phone is assigned on the server:

```sh
xi clients                          # devices, with their user if assigned
xi clients user iPhone alice        # by name or key prefix
xi clients user iPhone -            # back to unassigned
```

An assignment applies the next time the device connects and wins over
whatever the device claims. A browser without one can claim an id itself by
setting the `xi-user` key in its local storage; otherwise it is `root`.

An id is lowercase letters, digits, `.`, `_` or `-`, up to 64 characters.
Anything else counts as `root`.

That is all the server does with users. Display names, roles and real
authentication are left to [extensions](extensions.md): every event a
client sends carries the sender's user id, and a room's member list is
part of its state, so an extension can build on them.

## Chats keep running

A chat whose agent is working stays open with nobody watching. Close the
laptop, and the task finishes; the result waits in the chat list. A chat only
closes when it is idle and empty.

## Starting a chat over HTTP

Another program can start a chat without a client attached:

```sh
curl -sX POST http://localhost:7474/api/rooms \
  -H "Authorization: Bearer $(cat ~/.config/xi/client-key)" \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"Run the tests and fix what fails","cwd":"/home/me/my-app"}'
```

```json
{"room-id": "r-…", "session-id": "01a0…", "cwd": "/home/me/my-app",
 "url": "http://localhost:7474/chat/01a0…"}
```

The key goes in `Authorization: Bearer <key>` or `X-Xi-Client-Key`; any
approved key works, and the local `client-key` file is one. The body is
JSON, every field optional:

| Field | Does |
| --- | --- |
| `prompt` | The first message; with it, the agent starts at once |
| `cwd` | The directory to work in; default the server's |
| `model` | A model; default the server's |

`401` is a bad key, `405` a method other than POST, `400` a body that is
not JSON. The same endpoint is served on the HTTPS port. Open the returned
URL to watch, or to continue the chat.

## HTTPS

Over plain HTTP an iPhone forgets a home-screen app's pairing on most
launches. Give the server a certificate and it serves `https://` on port 7443
as well:

```sh
mkdir -p ~/.config/xi/tls
# put xi.crt and xi.key there (mkcert works well for a home network)
xi server --headless
```

The phone needs to trust the certificate once. [HTTPS](https.md) walks
through it.

## Keeping it up

The server is a long-lived process, so run it under a supervisor: a systemd
user service, launchd, or a tmux session. If a stray error escapes, the
server logs it to `~/.config/xi/crash.log` and keeps running rather than
dropping every client.

## When something is off

**A phone cannot connect.** Check the address: the server prints the ones it
bound. A firewall on the workstation may block 7474.

**"Pairing required" every time on iPhone.** Serve over HTTPS; see above.

**A chat I closed is still listed as live.** Its agent is still working. Open
it to see, or wait; it closes when the turn ends.

## Reference

Every flag: [Command line](command-line.md). How rooms, broadcast and the
wire protocol work inside: [`docs/architecture.md`](../architecture.md) in the
repository.
