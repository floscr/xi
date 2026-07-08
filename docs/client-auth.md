# Client Authentication

Every WS client must prove itself with a **client key** before the server
processes any of its events. Unknown keys go into a pending state with a
4-digit pairing code; an already-authenticated client (web banner) or the
user at the terminal (`bb serve:approve <code>`) approves them.

## Threat model

WebSockets are **not subject to CORS** — without auth, any webpage you visit
could open `ws://localhost:7474` and send prompts, and prompts mean arbitrary
code execution (the agent has bash tools).

Client-key auth protects against:

- **Malicious webpages** — no key, plus cross-host `Origin` headers are
  refused at WS upgrade
- **Other users on the same machine** — key files are mode `0600`
- **Sandboxed processes** — can't read `~/.config/xi/`
- **Network peers** — e.g. anything that can reach the port over
  tailscale/LAN

It does **not** protect against malware running as the same user — such a
process can read the key files directly. That is out of scope: same-user
malware already owns everything the agent could do.

## Handshake

Auth is **transport-level**: `:auth/*` events are handled in the socket layer
(`xi.server.ws`) and never dispatched into app state or rooms.

```
client  → {:type :auth/hello :client-key "…" :client-name "iPhone (web)" :platform "web"}

server  → {:type :auth/ok}                        key approved → lobby state follows
        | {:type :auth/pending :code "1234"}      unknown key → parked; every other
                                                  event answered with {:type :auth/required}
        | {:type :auth/denied :reason …}          rejected; connection closed
```

While a request is pending:

- authed clients receive `{:type :auth/request :code :client-name :platform}`
  (the web client shows an approve/deny banner) and may answer with
  `{:type :auth/approve :code …}` or `{:type :auth/deny :code …}`
- the server **polls `clients.edn` every 2s**, so `bb serve:approve <code>`
  (which writes the file directly) admits the client within ~2s — no
  authenticated admin endpoint needed; the filesystem is the root of trust
- resolution is broadcast to authed clients as `{:type :auth/resolved}`

`:client-name` / `:platform` are client-claimed labels for display only —
the pairing-code comparison is the actual security.

## Keys and files

| Path | Purpose |
|---|---|
| `~/.config/xi/client-key` | The local TUI's key, generated on first run (0600). **Implicitly trusted** — anything that can read it already runs as the user. |
| `~/.config/xi/clients.edn` | Approved keys → `{:name :platform :approved-at :last-seen}` (0600). |
| `~/.config/xi/pending-clients.edn` | Pending requests mirrored for `bb serve:approve`; cleared on server start. |

The **web client** generates its key into `localStorage` (`xi-client-key`)
and derives its display name from the user agent ("iPhone (web)", "Linux
(web)", …). Each browser/device pairs once and stays approved.

## bb tasks

```bash
bb serve:pending          # list pending pairing requests (code, name, platform)
bb serve:approve <code>   # approve — server admits the client within ~2s
bb serve:clients          # list approved clients (key prefix, name, last seen)
bb serve:revoke <prefix|name>  # remove an approved client (applies to new connections)
```

## Notes

- The Origin check compares **hostnames only** (ports ignored) so the shadow
  dev-http page on `:8100` can still open the WS on `:7474`. Requests without
  an Origin header (non-browser clients) are allowed — key auth still gates
  them.
- The TUI never sees a pairing code in practice: it reads/creates
  `~/.config/xi/client-key`, which the server trusts implicitly.
- The TUI has no UI for approving *other* clients — approval happens via the
  web banner or `bb serve:approve`.
- Revocation applies to **new** connections; already-connected sockets stay
  admitted until they disconnect.

## Source

```
src/xi/auth.cljs               — key store (approved/pending files, code gen)
src/xi/server/ws.cljs          — handshake, Origin check, clients.edn poll
src/xi/client/ws_transport.cljs — client side: send hello, queue sends until :auth/ok
src/xi/web/core.cljs           — web key (localStorage), auth handlers
src/xi/web/views.cljs          — pairing overlay + approve/deny banner
bb.edn                         — serve:pending / approve / clients / revoke
```
