# MCP client internals

How Xi consumes external MCP servers. Using them (`/mcp`, `mcp.edn`, trust,
hosted servers, servers of an extension's own) is in the guide:
[MCP servers](guide/mcp-servers.md). The inverse direction, how Xi offers its
own tools to the Claude SDK, is [mcp-tool-bridge.md](mcp-tool-bridge.md).

There is no MCP-specific provider code. Two general mechanisms carry it:

1. **The runtime extension manager** (`xi.ext.manager`) keeps the assembled
   `ext/compose` snapshot live, so extensions can be registered, enabled and
   disabled mid-session (`/ext`), firing `:on-enable` / `:on-disable`. The
   provider re-reads the composed tool set every turn (the fn-valued tooling
   seam in `xi.cli/tooling-opts`, deref'd in
   `xi.tools.registry/resolve-tooling`), so **only** `:tool-definitions` and
   `:tool-registry` hot-swap; handlers, event hooks, commands, keybindings,
   system prompt, taps and routes are captured at assembly and need a
   restart. MCP wrappers only contribute tools, which is why it is built
   this way.
2. **MCP-as-extension** (`xi.ext.mcp`): each `mcp.edn` entry becomes an
   extension contributing `mcp__<id>__*` tools, registered into the manager.
   The wrapper strips the prefix before forwarding a call.

## Lifecycle and caching

- `/mcp add` connects once, calls `tools/list`, writes
  `~/.config/xi/mcp/<id>/tools.edn`. At startup tools are advertised from
  that cache synchronously; the subprocess is spawned lazily on the first
  call and shared by every room. When it exits, the next call respawns it.
  `/mcp disable` and shutdown kill it; stderr is drained (a full pipe would
  block it) and its tail is quoted when it dies.
- `:timeout-ms` (default 120000, `<= 0` disables) caps each request.
- Every `tools/call` carries `_meta` (`xi/cwd`, `xi/roomId`, `xi/clientPid`,
  `xi/extension`), see `xi.ext.mcp/call-meta`.
- `:hidden-tools` are cached but not advertised; `xi.api.mcp/call` from a
  user extension can still reach them.

## Trust

The confirm on untrusted servers is the `mcp-confirm` default rule
(`{:tool :mcp :mcp-trusted false}` → ask), not code in the extension; the
dialog's `[a]lways` dispatches the trust write. `xi.mcp.trust` stores a
sha256 per server in `~/.config/xi/ext/mcp-trust.edn` over the entry's
`:transport :command :args :url :cwd :code-paths` (not `:env`, `:enabled`,
`:timeout-ms`), the content of every existing file the command line names
(relative to `:cwd`), and every file under `:code-paths` (`.git`,
`node_modules` skipped). `:trusted-mcp-servers` in the user config trusts
an id as is, without a fingerprint. Extension-declared servers are
`"<extension>/<name>"`.

## Wire protocol

`xi.mcp.client` speaks MCP by hand (single-runtime-dep rule: no SDK).

**stdio**: newline-delimited JSON-RPC 2.0 over the subprocess's stdin/stdout.
Requests carry an integer `id`, responses echo it, notifications omit it.
Handshake `initialize` → `notifications/initialized`, then `tools/list` and
`tools/call`. A `tools/call` result `{content, isError}` is normalized to
`{:content :is-error}`.

**Streamable HTTP** (`connect-http`): each message is a `POST` to the
server's URL; the reply is an `application/json` body or a
`text/event-stream` frame. An `Mcp-Session-Id` assigned on `initialize` is
echoed on later requests. Auth is an `:auth` descriptor on the entry
(`{:ext-config "render" :key "RENDER_API_KEY" :header "Authorization"
:scheme "Bearer"}`) resolved at connect time by `xi.ext.config/get-value`
from `~/.config/xi/ext/<id>.env`; a missing key omits the header and the
server's `401` surfaces. OAuth (`/mcp auth`) is not implemented.

## MCP Apps (SEP-1865)

`xi.mcp.client` sends `capabilities.extensions["io.modelcontextprotocol/ui"]
{:mimeTypes ["text/html;profile=mcp-app"]}` in `initialize` and has
`read-resource`. `xi.ext.mcp/tool-ui` reads a tool's `_meta.ui`
(`resourceUri`, `visibility`; also the deprecated flat
`_meta["ui/resourceUri"]`): `normalize-tool-def` carries it as `:ui`
(`xi.tools.registry/resolve-tooling` strips it, and drops defs whose
visibility leaves out `"model"`), app-only tools are left out of the
extension's defs and registry, and the per-server `apps` atom keeps every
tool's meta plus the connection's `:call` / `:read` for the views
(`app-tool`, `read-resource!`). `normalize-result` keeps `structuredContent`
as `:structured-content`, which the tool pipeline never passes to a model.
After a tool with a `resourceUri` ran for the agent, its registry fn
dispatches `:ext.blocks/app-open`; `xi.ext.blocks.app` turns that into an
agent block (reading the resource, caching it per server + URI) and answers
the browser's frame host (`xi.ext.blocks.frame`): HTML on
`:ext.blocks/app-load`, the view's `tools/call` (rules-gated, only
`app-tool`s), `resources/read` and `ui/update-model-context` on
`:ext.blocks/app-rpc`. The web host replaces the spec's sandbox proxy with a
`sandbox="allow-scripts"` srcdoc iframe (an opaque origin) and the resource's
CSP as the document's first `<meta>` (`xi.blocks.csp`). Not implemented:
`tool-input-partial` / `tool-cancelled` (a view opens after its call
finished), `host-context-changed` on a theme switch, `permissions`,
`domain`, `pip`. The Babashka example server's `uuids` tool is a reference
view, exercised by `test/xi/e2e/blocks_e2e.cljs`.

## Servers of a user extension

`:mcp-servers` on a user extension map declares private servers in
`mcp.edn` shape. They are not offered to the agent, only reachable through
`xi.api.mcp/call` from that extension, started on first use and stopped on
unmount. Validation: simple keyword names, stdio `:command` with string
`:args`/`:env`/`:cwd`, or `:transport :http` with an http(s) `:url`.

## Writing a server

Two dependency-free implementations live in the repo: `xi.mcp.server`
(`src/xi/mcp/server.cljs`, the counterpart of the client, usable from any
project with xi on its classpath) and `packages/mcp-bb-example` (Babashka;
`bb mcp:bb-example:test` runs its tests including a stdio round trip).
Conventions: stdout is protocol only; answer `initialize`, `ping`,
`tools/list`, `tools/call`; no reply to `notifications/*`; `-32601` for
unknown methods; tool failures are results with `isError: true`.

## Source

- `src/xi/ext/manager.cljs` — live extension registry
- `src/xi/ext/extensions.cljs` — the `/ext` command
- `src/xi/ext/mcp.cljs` — MCP-as-extension, registry/cache I/O, `/mcp`, `:auth`
- `src/xi/ext/config.cljs` — per-extension dotenv secrets
- `src/xi/mcp/client.cljs` — stdio `connect` + `connect-http`
- `src/xi/mcp/server.cljs` — stdio server for ClojureScript servers
- `src/xi/mcp/trust.cljs` — fingerprints
- `src/xi/api/mcp.cljs` — `xi.api.mcp/call` for user extensions
- `src/xi/cli.cljs` — creates the manager, `mcp/install!`, `tooling-opts`
