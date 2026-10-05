# MCP Servers (consuming external MCP tools)

Xi can consume tools from **external MCP servers** and expose them to the agent
alongside its built-in tools. This is the *inverse* of the
[MCP Tool Bridge](mcp-tool-bridge.md): that doc describes how Xi advertises its
*own* tools to Claude; this doc describes how Xi pulls in *other* servers' tools.

The whole feature is built on two general mechanisms, neither of which is
MCP-specific:

1. **Runtime extension enable/disable** — a live extension registry
   (`xi.ext.manager`) that lets extensions be toggled on and off mid-session,
   with `:on-enable` / `:on-disable` lifecycle hooks (`/ext` command).
2. **MCP-as-extension** — each configured MCP server is wrapped as an ordinary
   Xi extension (`xi.ext.mcp`) contributing `:tool-definitions` +
   `:tool-registry`, then registered into the manager.

There is no MCP-specific provider code. An MCP server is just a bag of tools
reachable over a wire protocol — exactly what Xi's extension tool surface
already models.

## The runtime extension manager

Assembly (`xi.cli`) normally freezes one `ext/compose` snapshot for the whole
process. `xi.ext.manager` keeps that snapshot **live**: it holds the registered
extensions plus which are enabled, and recomposes on every change. The provider
tooling seam reads the composition fresh on every turn, so an extension's tools
appear or vanish the moment it is enabled/disabled — no restart.

### Honest scope: only tool surfaces hot-swap

The provider re-reads `:tool-definitions` and `:tool-registry` per turn (see `xi.tools.registry/resolve-tooling`, which derefs the
fn-valued tooling seam supplied by `xi.cli/tooling-opts`). Those surfaces go live
immediately.

Everything else — reducer `:handlers`, `:event-hooks`, command dispatch,
`:keybindings`, `:system-prompt`, `:taps`, `:routes` — is captured **once** into
`create-app` / the TUI client / the WS server at assembly time. Toggling an
extension that contributes those surfaces does **not** fully take effect until a
restart. MCP extensions only ever contribute tools, so they are
covered completely; that is why the feature is built this way.

### `/ext` command

```
/ext list              # list every registered extension with [x]/[ ] enabled
/ext enable <id>       # enable an extension by id
/ext disable <id>      # disable an extension by id
```

Changes "take effect on the next turn" (the wording the status line uses),
because the provider reads the composition when it builds each turn's tool set.

### Lifecycle hooks

An extension map may carry:

- `:on-enable  (fn [])` — fired when it is enabled at runtime (via `/ext` or
  `/mcp`). **Not** fired during the initial seed or at assembly.
- `:on-disable (fn [])` — fired when it is disabled or removed; the extension
  owns its own teardown here (e.g. an MCP wrapper closes its subprocess).

## MCP servers

### Registry: `~/.config/xi/mcp.edn`

An EDN map keyed by server id:

```clojure
{:context7 {:transport :stdio
            :command   "npx"
            :args      ["-y" "@upstash/context7-mcp"]
            :enabled   true}
 :render   {:transport :http
            :url       "https://mcp.render.com/mcp"
            ;; API key resolved at connect time from the gitignored
            ;; per-extension config — never stored here (see below):
            :auth      {:ext-config "render" :key "RENDER_API_KEY"
                        :header "Authorization" :scheme "Bearer"}
            :enabled   false}}
```

- `:transport` — `:stdio` (spawn a subprocess) or `:http` (POST to a hosted
  server, [Streamable HTTP](#http-transport-streamable-http--api-key)).
- `:command` / `:args` / `:env` / `:cwd` — how to spawn a stdio server.
- `:url` — the endpoint of an `:http` server.
- `:headers` — literal headers for an `:http` server (a clj map).
- `:auth` — an `:http` server's auth *descriptor* (never the secret itself);
  see [HTTP transport](#http-transport-streamable-http--api-key).
- `:enabled` — load it at startup (default `true` when the key is absent).
- `:timeout-ms` — cap on each request to the server (default `120000`; `<= 0`
  disables), so a wedged server surfaces as an error instead of hanging the
  turn. Raise it for tools that wait on the user.
- `:code-paths` — files or directories that are part of the server's code
  besides the files its command line names; see
  [Trusting a server](#trusting-a-server-once-until-its-code-changes).
- `:hidden-tools` — tool names not offered to the agent. They stay callable by
  user extensions ([below](#calling-servers-from-user-extensions)), for
  servers that have tools meant for an extension rather than the model.

### Tool cache: `~/.config/xi/mcp/<id>/tools.edn`

When a server is added (or refreshed), Xi connects once, calls `tools/list`,
and writes the discovered tools to disk. On the next start the tools advertise
**synchronously** from this cache; the subprocess is only spawned **lazily** on
the first actual tool call. This keeps startup fast and avoids spawning servers
that are never used in a session.

### Tool namespacing

Discovered tools are advertised as `mcp__<id>__<tool>` (the ecosystem
convention) so they never collide with Xi's built-in tools. The wrapper strips
the `mcp__<id>__` prefix before forwarding the call to the server.

### Tool gate: untrusted servers are confirmed, trusted ones aren't

External MCP servers are third-party code, so **a server runs nothing until you
approve it.** This gate is a **built-in default rule** in the
[rules engine](rules.md) — `{:match {:tool :mcp :mcp-trusted false} :action
{:type :ask …}}` — not code in the `:mcp` extension. It matches every
`mcp__<id>__<tool>` call to a server that isn't trusted (built-in Xi tools with
bare names are untouched) and raises a confirm dialog before the call is
forwarded. The rules ext builds an informative block carrying as much info as
possible — the server, the tool, and every argument:

```
MCP tool call — approve?

Server: context7
Tool:   get-library-docs

Arguments:
  context7CompatibleLibraryID: /facebook/react
  topic: hooks
```

Approve and the call proceeds; deny and it is blocked. In headless mode with no
client attached to approve, the confirm resolves to its safe default (deny), so
an untrusted server never runs unattended.

### Trusting a server: once, until its code changes

The dialog's third choice — `[a]lways` in the TUI, **Always (trust this
server)** on the web — approves the call *and trusts the server*: from then
on its calls run without asking, in every room and for user extensions too
(`xi.api.mcp`), across restarts. `/mcp trust <id>` does the same without
waiting for a call; `/mcp untrust <id>` takes it back; `/mcp list` shows which
servers are trusted.

Trust is content-addressed (`xi.mcp.trust`), like `bb.edn` trust: it records a
sha256 of the server's code, and **any change to that code asks again**. The
fingerprint covers

- the entry's `:transport`, `:command`, `:args`, `:url`, `:cwd` and
  `:code-paths` (not `:env` / `:enabled` / `:timeout-ms`), so an edited entry
  asks again;
- the content of every file the `:command` or `:args` name (a value with a `/`
  that is an existing file, relative to `:cwd`) — e.g. the built bundle a
  `bun` / `node` server runs, so a rebuild with changed code asks again;
- every file under the entry's `:code-paths` (files or directories; `.git`
  and `node_modules` skipped), for code the command line doesn't name, such as
  a bb server's `src/`.

Code a command downloads (`npx -y pkg@latest`) is outside the fingerprint:
pin a version (`pkg@1.2.3`) so an upgrade is an entry change. The store is
`~/.config/xi/ext/mcp-trust.edn` (`{:servers {"<id>" "<sha256>"}}`).

**In config.** To trust a server for good, list it in `~/.config/xi/config.edn`
(a file agents can't write):

```clojure
:trusted-mcp-servers ["chrome" "product-search/browser"]
```

A listed server is trusted as it is, with no fingerprint (rebuilding it never
asks): the config is the decision. Extension servers go by their
`"<extension>/<name>"` id. `/mcp list` says which trust applies.

Because the gate is an ordinary default rule, you can still override it: an
`:ask` / `:deny` rule of your own for a server or tool applies whether or not
the server is trusted.

### `/mcp` command

```
/mcp list                          # list configured servers, transport, tool count, [x]/[ ]
/mcp add <id> <command> [args...]  # add a stdio server: connect, cache its tools, enable it
/mcp enable <id>                   # enable a server (persists :enabled true)
/mcp disable <id>                  # disable a server (persists :enabled false)
/mcp remove <id>                   # unregister + delete its registry entry and tool cache
/mcp refresh <id>                  # reconnect, re-cache tools, re-register
/mcp trust <id>                    # run its tools without asking, until its code changes
/mcp untrust <id>                  # ask again
/mcp auth <id>                     # OAuth for hosted servers — not implemented yet
```

Example:

```
/mcp add context7 npx -y @upstash/context7-mcp
```

adds a stdio MCP server `context7`, connects, caches its tools, and registers it
enabled — its `mcp__context7__*` tools are available on the next turn.

### Turn context: `_meta`

Every `tools/call` carries the calling turn's context in the request's
`_meta` (the MCP spec's slot for out-of-band data), so a server can act on it
without a tool argument the model would have to fill in:

| Key | Value |
|---|---|
| `xi/cwd` | the room's working directory |
| `xi/roomId` | the room making the call |
| `xi/clientPid` | OS pid of the client driving the room (a TUI), when there is one; sub-agents pass their parent's |
| `xi/extension` | the user extension making the call, when it isn't the agent |

Absent values are left out. See `xi.ext.mcp/call-meta`.

### Lifecycle

A stdio server is spawned on its first call and shared by every room. When it
exits, the next call starts a new one. xi kills it on `/mcp disable` and at
shutdown; a well-behaved server also exits when its stdin closes. Its stderr
is drained (a full pipe would block it), and the tail is quoted when it dies.

## Calling servers from user extensions

`xi.api.mcp/call` lets a [user extension](user-extensions.md) call a
configured server's tools over the agent's own connection, so a stateful
server (a browser) is shared rather than started twice:

```clojure
(mcp/call ctx :chrome "list_pages" {})          ; → Promise<{:content … :is-error …}>
(mcp/call ctx :chrome "design_poll" {:pageId 3} {:room-id room-id})
```

Each call is a rules request `{:tool :mcp :mcp-server :mcp-tool :arguments}`
tagged with the extension, so the same gate applies: a trusted server's calls
run, an untrusted one asks. An extension usually calls from an effect, where
there is no dialog to answer, so trust the server first (one `[a]lways` on an
agent call, or `/mcp trust <id>`). Disabled or unknown servers reject.

### Servers an extension declares

An extension can also bring MCP servers of its own (`:mcp-servers` in its
map, entries shaped like `mcp.edn`'s). Those are private to it: not offered to
the agent, callable only by that extension, started on first use and stopped
when it unmounts. They are `"<extension>/<name>"` to the rules and to trust.
See [user-extensions.md](user-extensions.md#mcp-servers-of-your-own).

## Writing a server

An MCP server is a process that reads JSON-RPC requests from stdin, one per
line, and writes replies to stdout. Conventions worth keeping (they're what
the spec, [modex](https://github.com/theronic/modex) and
[mcp-clj](https://github.com/hugoduncan/mcp-clj) do):

- stdout carries protocol only; log to stderr.
- Answer `initialize` (`protocolVersion`, `capabilities {tools {}}`,
  `serverInfo`), `ping`, `tools/list`, `tools/call`; send no reply to
  `notifications/*`; unknown methods get JSON-RPC error `-32601`.
- A tool that fails is a **result** with `isError: true`, so the model sees
  why. JSON-RPC errors are for protocol faults.
- Read context from `_meta`, not from extra tool arguments.

Two small implementations live in this repo, both dependency-free:

- **ClojureScript (bun/node):** `xi.mcp.server` (`src/xi/mcp/server.cljs`),
  the counterpart of xi's client. A server built on it can live anywhere
  that has xi on its classpath (e.g. a `:local/root` dependency).
- **Babashka:** [`packages/mcp-bb-example`](../packages/mcp-bb-example/)
  (`mcp.server` + two example tools, one reading `_meta` `xi/cwd`).
  `bb mcp:bb-example:test` runs its tests, including a stdio round trip.
  Register it with:

  ```clojure
  {:bb-example {:command "bb"
                :args ["--config" "/path/to/xi/packages/mcp-bb-example/bb.edn"
                       "-m" "hello-mcp.main"]
                ;; -m names a namespace, not a file: list the code so a
                ;; change to it re-asks (see "Trusting a server")
                :code-paths ["/path/to/xi/packages/mcp-bb-example/src"]}}
  ```

### Example: browser tools

Browser automation needs no xi code: register the upstream
`chrome-devtools-mcp` server (pin a version, so an upgrade re-asks for
trust):

```clojure
{:chrome {:command "npx" :args ["-y" "chrome-devtools-mcp@1.8.0"]}}
```

## Wire protocol (stdio)

`xi.mcp.client` speaks MCP by hand (Xi's single-runtime-dep rule means no MCP
SDK). stdio MCP is newline-delimited JSON-RPC 2.0:

1. spawn the server; write one JSON object per line to its stdin
2. read its stdout, one JSON object per line
3. requests carry an integer `id`; responses echo it; notifications omit it

Handshake: `initialize` request → `notifications/initialized` notify. Then
`tools/list` to discover tools and `tools/call` to invoke one. An MCP
`tools/call` result (`{content, isError}`) is normalized to Xi's tool-result
shape (`{:content [...] :is-error bool}`).

## HTTP transport (Streamable HTTP + API key)

Hosted MCP servers (e.g. Render's `https://mcp.render.com/mcp`) speak the
**Streamable HTTP** transport: each JSON-RPC message is a `POST` to the server's
URL, and the reply is either an `application/json` body or a `text/event-stream`
(SSE) frame carrying the response. `xi.mcp.client/connect-http` implements this
natively over `fetch` — **no npm bridge, no OAuth flow.** A server that assigns
an `Mcp-Session-Id` on `initialize` gets it echoed on every subsequent request.

Authentication is by **API key** via an `Authorization: Bearer <key>` header.
The key is **never** stored in `mcp.edn`; the entry carries only an `:auth`
*descriptor* naming where to read it and how to shape the header:

```clojure
:auth {:ext-config "render"          ;; which per-extension config to read
       :key        "RENDER_API_KEY"  ;; the KEY in that config
       :header     "Authorization"   ;; header to set
       :scheme     "Bearer"}         ;; optional prefix → "Bearer <key>"
```

At connect time `xi.ext.mcp` resolves the secret with
`xi.ext.config/get-value` and adds the header. If the key is missing the header
is omitted and the server's `401` surfaces as a clear error.

### Per-extension config (gitignored secrets)

Secrets live in a dotenv-style file, **outside the repo**, one per extension:

```
# ~/.config/xi/ext/render.env
RENDER_API_KEY=rnd_your_key_here
```

`xi.ext.config` reads `~/.config/xi/ext/<id>.env` (`KEY=VALUE` lines; `#`
comments and blank lines ignored; surrounding quotes stripped). A non-blank
`process.env` value of the same name overrides the file, so a shell `export`
works too. The repo's `.gitignore` also covers stray `*.env` files as a
backstop. This loader is generic — any extension can use it for its own
secrets, not just MCP.

### Example: Render

Render's hosted MCP server puts all of the above together. Its tools can
trigger deploys and change service env vars, so add it disabled and opt in when
you need it:

1. Add the `:render` entry from [the registry example](#registry-configxi-mcpedn) to `~/.config/xi/mcp.edn`
   with `:enabled false`.
2. Put your key in `~/.config/xi/ext/render.env` (`RENDER_API_KEY=…`).
3. `/mcp enable render`, then `/mcp refresh render` to cache its tools.

Once enabled, Render tool calls are confirmed by the MCP default rule until you
trust the server (`[a]lways` on one call, or `/mcp trust render`). A hosted
server's code isn't on your disk, so its trust covers only the entry (its
`:url`). Render's tools can deploy and change env vars — if you'd rather keep
confirming those while trusting the reads, add an `:ask` rule for the risky
tools, e.g. `{:match {:tool :mcp :mcp-server "render" :mcp-tool "*deploy*"}
:action {:type :ask}}`.

The hosted-OAuth flow (`/mcp auth`) remains unimplemented — API-key auth covers
Render and most hosted servers without it.

## Source

- `src/xi/ext/manager.cljs` — live extension registry + enable/disable/register/unregister
- `src/xi/ext/extensions.cljs` — the `/ext` control command
- `src/xi/ext/mcp.cljs` — MCP-as-extension helper + registry/cache I/O + `/mcp` command + `:auth` resolution
- `src/xi/ext/config.cljs` — generic per-extension gitignored config/secret loader
- `src/xi/mcp/client.cljs` — JSON-RPC MCP client (stdio `connect` + Streamable-HTTP `connect-http`)
- `src/xi/mcp/server.cljs` — stdio MCP server for ClojureScript servers
- `src/xi/api/mcp.cljs` — `xi.api.mcp/call` for user extensions
- `src/xi/cli.cljs` — creates the manager, seeds it, calls `mcp/install!`, and
  wires the fn-valued tooling seam (`tooling-opts`)
- `src/xi/tools/registry.cljs` — `resolve-tooling` derefs the tooling seam per turn
