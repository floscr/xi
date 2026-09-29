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
per turn (see `xi.providers.anthropic/resolve-tooling`, which derefs the
fn-valued tooling seam supplied by `xi.cli/tooling-opts`). Those surfaces go live
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
an explicit approval.** This gate is a **built-in default rule** in the
[rules engine](rules.md) — `{:match {:tool :mcp} :action {:type :ask …}}` — not
code in the `:mcp` extension. It matches every `mcp__<id>__<tool>` call (built-in
Xi tools with bare names are untouched) and raises a confirm dialog before the
call is forwarded. The rules ext builds an informative block carrying as much
info as possible — the server, the tool, and every argument:

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
MCP tools never run unattended.

**Allow always (per session).** The dialog offers a third choice besides
yes/no — `[a]llow always` in the TUI, an **Always** button on the web. Choosing
it approves this call *and* persists a session allow-rule narrowed to that MCP
server + tool (`{:match {:tool :mcp :mcp-server … :mcp-tool …} :action {:type
:allow}}`), so every later call to that same tool skips the prompt. The rule is
session-scoped (room-scoped runtime state): it lives as long as the room does
and is cleared when the session ends. To make it permanent, commit an `:allow`
rule to `~/.config/xi/rules.edn` or `<repo>/.xi/rules.edn` (see
[rules.md](rules.md)).

Because it is an ordinary default rule, you can override it: a higher-precedence
`:allow` rule for a server/tool silences the prompt, and an `:ask`/`:deny` rule
of your own can tighten or widen it.

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

### Render extension

`xi.ext.render` is a dedicated, **disabled-by-default** extension that wires all
of the above together. On startup it idempotently seeds the disabled `:render`
`:http` entry (above) into `mcp.edn` — so Render's tools, which can trigger
deploys and mutate service env vars, never load unless you opt in. To use it:

1. Put your key in `~/.config/xi/ext/render.env` (`RENDER_API_KEY=…`).
2. `/mcp enable render` then `/mcp refresh render` (caches its tools).
3. `/render` shows key presence, enabled state, and these steps.

Once enabled, every Render tool call is still confirmed by the MCP default rule —
unless you `[a]llow always` a given tool, which persists a session allow-rule for
it (e.g. approve `list_logs` once with `[a]` and later log reads run un-prompted).

The hosted-OAuth flow (`/mcp auth`) remains unimplemented — API-key auth covers
Render and most hosted servers without it.

## Source

- `src/xi/ext/manager.cljs` — live extension registry + enable/disable/register/unregister
- `src/xi/ext/extensions.cljs` — the `/ext` control command
- `src/xi/ext/mcp.cljs` — MCP-as-extension helper + registry/cache I/O + `/mcp` command + `:auth` resolution
- `src/xi/ext/render.cljs` — the disabled-by-default Render (`:http`) extension
- `src/xi/ext/config.cljs` — generic per-extension gitignored config/secret loader
- `src/xi/mcp/client.cljs` — JSON-RPC MCP client (stdio `connect` + Streamable-HTTP `connect-http`)
- `src/xi/cli.cljs` — creates the manager, seeds it, calls `mcp/install!`, and
  wires the fn-valued tooling seam (`tooling-opts`)
- `src/xi/providers/anthropic.cljs` — `resolve-tooling` derefs the tooling seam per turn
