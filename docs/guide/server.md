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
too. The server picks approvals up within two seconds.

A browser page from another site can never reach the server: the key is
required before any message is processed, and the server refuses connections
from other origins.

## Chats keep running

A chat whose agent is working stays open with nobody watching. Close the
laptop, and the task finishes; the result waits in the chat list. A chat only
closes when it is idle and empty.

Starting work without a client at all is possible too:

```sh
curl -sX POST http://localhost:7474/api/rooms \
  -H "Authorization: Bearer $(cat ~/.config/xi/client-key)" \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"Run the tests and fix what fails","cwd":"/home/me/my-app"}'
# → {"session-id":"…","url":"http://localhost:7474/chat/…"}
```

Open the returned URL to watch, or to continue.

## HTTPS

Over plain HTTP an iPhone forgets a home-screen app's pairing on most
launches. Give the server a certificate and it serves `https://` on port 7443
as well:

```sh
mkdir -p ~/.config/xi/tls
# put xi.crt and xi.key there (mkcert works well for a home network)
xi server --headless
```

The phone needs to trust the certificate once. The reference page walks
through it: [TLS and HTTPS](../tls-https.md).

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

Bind addresses, the HTTP API, the room lifecycle and the wire protocol:
[the server reference](../server.md). Pairing in depth:
[client authentication](../client-auth.md).
