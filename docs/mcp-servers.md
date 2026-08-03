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

The provider re-reads `:tool-definitions`, `:tool-registry`, and `:tool-gate`
per turn (see `xi.provider.claude/build-mcp-server`, which derefs the fn-valued
tooling seam supplied by `xi.cli/tooling-opts`). Those surfaces go live
immediately.

Everything else — reducer `:handlers`, `:event-hooks`, command dispatch,
`:keybindings`, `:system-prompt`, `:taps`, `:routes` — is captured **once** into
`create-app` / the TUI client / the WS server at assembly time. Toggling an
extension that contributes those surfaces does **not** fully take effect until a
restart. MCP extensions only ever contribute tools + a tool-gate, so they are
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
 :render   {:transport :http                 ;; OAuth — not yet implemented
            :url       "https://mcp.render.com/mcp"
            :enabled   true}}
```

- `:transport` — `:stdio` (implemented) or `:http` (planned; needs OAuth).
- `:command` / `:args` / `:env` / `:cwd` — how to spawn a stdio server.
- `:enabled` — load it at startup (default `true` when the key is absent).

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

### Tool gate: every MCP tool call is confirmed

External MCP servers are third-party code, so **nothing they expose runs without
an explicit approval.** The `:mcp` control extension carries a `:tool-gate` that
intercepts every `mcp__<id>__<tool>` call (built-in Xi tools with bare names are
untouched) and raises a confirm dialog before the call is forwarded. The block
carries as much info as possible — the server, the tool, and every argument:

```
MCP tool call — approve?

Server: context7
Tool:   get-library-docs

Arguments:
  context7CompatibleLibraryID: /facebook/react
  topic: hooks
```

Approve and the call proceeds; deny and it is blocked (the model gets
"Blocked by Xi permission gate"). In headless mode with no client attached to
approve, the confirm resolves to its safe default (deny), so MCP tools never run
unattended. The gate lives alongside the built-in `permission-gate`; both are
composed into the same per-turn tool-gate chain.

### `/mcp` command

```
/mcp list                          # list configured servers, transport, tool count, [x]/[ ]
/mcp add <id> <command> [args...]  # add a stdio server: connect, cache its tools, enable it
/mcp enable <id>                   # enable a server (persists :enabled true)
/mcp disable <id>                  # disable a server (persists :enabled false)
/mcp remove <id>                   # unregister + delete its registry entry and tool cache
/mcp refresh <id>                  # reconnect, re-cache tools, re-register
/mcp auth <id>                     # OAuth for hosted servers — not implemented yet
```

Example:

```
/mcp add context7 npx -y @upstash/context7-mcp
```

adds a stdio MCP server `context7`, connects, caches its tools, and registers it
enabled — its `mcp__context7__*` tools are available on the next turn.

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

## HTTP / OAuth transport (planned)

Hosted MCP servers (e.g. Render's `https://mcp.render.com/mcp`) use the
streamable-HTTP transport with OAuth 2.1 (PKCE, dynamic client registration per
RFC 7591, authorization-server metadata per RFC 8414, loopback redirect). That
transport and the `/mcp auth` flow are not yet implemented; `:http` entries
currently error with a "needs OAuth" message and `/mcp auth` is a stub.

## Source

- `src/xi/ext/manager.cljs` — live extension registry + enable/disable/register/unregister
- `src/xi/ext/extensions.cljs` — the `/ext` control command
- `src/xi/ext/mcp.cljs` — MCP-as-extension helper + registry/cache I/O + `/mcp` command
- `src/xi/mcp/client.cljs` — stdio JSON-RPC MCP client
- `src/xi/cli.cljs` — creates the manager, seeds it, calls `mcp/install!`, and
  wires the fn-valued tooling seam (`tooling-opts`)
- `src/xi/provider/claude.cljs` — `build-mcp-server` derefs the tooling seam per turn
