# Chrome DevTools MCP (`xi.ext.chrome`)

Proxies [`chrome-devtools-mcp`](https://github.com/ChromeDevTools/chrome-devtools-mcp)
into xi's own tool surface, so an agent running through the Claude bridge can
drive a browser — navigate pages, click, snapshot the a11y tree, run
performance traces, read console/network — as ordinary xi tools.

## Why this exists

xi's Claude provider **disables the Claude CLI's native MCP servers**: only
xi's in-process MCP is exposed to a run (see `xi.provider.claude/query-opts`).
So a `chrome-devtools-mcp` you declare in `~/.claude.json` never reaches an
agent turn started *inside* xi.

This extension re-provides it. xi spawns `chrome-devtools-mcp` as a child MCP
server over stdio and forwards each call. The payoff: every browser call flows
through xi's **tool-gate**, so it's governable and redirectable exactly like a
built-in tool (e.g. a future rewrite that reuses an already-open tab, or places
the browser window on the terminal's workspace).

```
model → xi in-process MCP (mcp__xi-tools__navigate_page …)
      → tool-gate → chrome registry fn
      → hand-rolled stdio JSON-RPC client → chrome-devtools-mcp → Chrome
```

The stdio client is hand-rolled (~90 lines, newline-delimited JSON-RPC 2.0) so
xi keeps its single runtime dependency rule — no `@modelcontextprotocol/sdk` is
added (see AGENTS.md).

## Enabling it

Opt-in via env — the factory returns `nil` unless `XI_CHROME_TOOLS` is set, so
the 29 browser tools aren't advertised on every turn by default.

| Env var | Effect |
|---------|--------|
| `XI_CHROME_TOOLS` | Enable the extension (any non-empty value). |
| `XI_CHROME_BROWSER_URL` | Attach to an existing Chrome's remote-debugging URL (passed as `--browserUrl`) instead of letting chrome-devtools-mcp launch its own. |
| `XI_CHROME_MCP_ARGS` | Extra CLI args for `chrome-devtools-mcp`, space-split. |

Chrome is launched **lazily on the first tool call** and killed on shutdown.
The connect promise is memoized, and cleared on failure so a later call
retries.

Because it's a server-side extension, set the env before starting the server
and pick it up with `bb serve:restart`:

```bash
XI_CHROME_TOOLS=1 bb serve:restart
```

### NixOS / non-standard Chrome path

`chrome-devtools-mcp` looks for Chrome at `/opt/google/chrome/chrome`. On NixOS
(or anywhere Chrome isn't at the default path) point it at your binary via
`XI_CHROME_MCP_ARGS`:

```bash
XI_CHROME_TOOLS=1 \
XI_CHROME_MCP_ARGS="--executablePath /etc/profiles/per-user/$USER/bin/google-chrome-stable" \
bb serve:restart
```

Or attach to an already-running Chrome started with
`--remote-debugging-port=9222`:

```bash
XI_CHROME_TOOLS=1 XI_CHROME_BROWSER_URL=http://127.0.0.1:9222 bb serve:restart
```

## Tool definitions are baked at compile time

xi collects `:tool-definitions` **synchronously at assembly** — there is no
async seam to discover chrome's tool list via `tools/list` per run. So the 29
tool defs are inlined into the bundle at compile time:

- `resources/chrome/tools.edn` — the generated defs (committed).
- `xi.ext.chrome-defs` — a `.clj` macro that `slurp`s + inlines that EDN
  (mirrors `xi.highlight.bundle`; `resources/` isn't on the classpath, so the
  macro reads the file by repo-relative path at compile time).
- `scripts/sync-chrome-tools.mjs` — regenerates the EDN from
  `chrome-devtools-mcp`'s live `tools/list`, transforming each `inputSchema`
  into xi's expected shape (structural keys as keywords, property-name keys as
  strings, so the JSON-Schema→Zod converter in `xi.provider.claude` handles
  nested `:required` correctly).

Regenerate when bumping `chrome-devtools-mcp`:

```bash
bb chrome:sync-tools   # → rewrites resources/chrome/tools.edn; rebuild to inline
```

## Files

| Path | Role |
|------|------|
| `src/xi/ext/chrome.cljs` | The extension: stdio JSON-RPC client + forward registry. |
| `src/xi/ext/chrome_defs.clj` | Compile-time macro inlining the tool defs. |
| `resources/chrome/tools.edn` | Generated tool defs (29 tools). |
| `scripts/sync-chrome-tools.mjs` | Regenerator (`bb chrome:sync-tools`). |
