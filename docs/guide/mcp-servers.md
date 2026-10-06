# MCP servers

Any MCP server can give the agent tools. Add it with one command, approve it
once, and its tools are there on the next turn, under the same rules as every
other tool.

## Add a server

```text
/mcp add browser npx -y chrome-devtools-mcp@1.10.1 --headless
```

Xi starts the server once, asks it for its tools, writes them to a cache, and
registers them as `mcp__browser__<tool>`. From the next turn on, the agent
can call them. The server process itself is only started when a tool is
first called, so an unused server costs nothing.

`/mcp list` shows the servers you have, how many tools each offers, and
whether it is trusted.

```text
/mcp disable browser    # keep it registered, offer no tools
/mcp enable browser
/mcp refresh browser    # re-read its tool list after an update
/mcp remove browser
```

Pin a version in the command, as above. A server installed as `@latest`
changes under you, and Xi cannot see that it did.

## Trusting a server

A server you just added runs nothing yet. Its first tool call shows a dialog
with the server, the tool and every argument:

```text
MCP tool call — approve?

Server: browser
Tool:   navigate_page

Arguments:
  type: url
  url: https://example.com
```

`y` runs this call. `a` (**Always**) runs it and trusts the server: its calls
stop asking, in every chat, until the server's code changes. Xi records a
fingerprint of the server's entry and the files its command names; an edit or
a rebuild asks again. `/mcp trust <id>` does the same without waiting for a
call, `/mcp untrust <id>` takes it back.

To trust a server for good, name it in your [config](configuration.md):

```clojure
:trusted-mcp-servers ["browser"]
```

A server listed there is trusted as it is, rebuilds included.

Trust and rules combine. To keep confirming one risky tool on a trusted
server, add a [rule](rules.md):

```clojure
{:match  {:tool :mcp :mcp-server "render" :mcp-tool "*deploy*"}
 :action {:type :ask}}
```

## The registry file

`/mcp add` writes to `~/.config/xi/mcp.edn`. You can edit it by hand:

```clojure
{:browser  {:transport :stdio
            :command   "npx"
            :args      ["-y" "chrome-devtools-mcp@1.10.1" "--headless"]}

 :docs     {:transport :stdio
            :command   "bb"
            :args      ["-m" "docs.server"]
            :cwd       "~/code/docs-mcp"
            ;; code the command line doesn't name, so a change re-asks
            :code-paths ["~/code/docs-mcp/src"]
            :enabled   false}}
```

| Key | Does |
| --- | --- |
| `:command` `:args` `:env` `:cwd` | How to start a stdio server |
| `:enabled` | `false` keeps it registered but off |
| `:timeout-ms` | How long one call may take; default two minutes |
| `:code-paths` | Files that count as the server's code for the trust fingerprint |
| `:hidden-tools` | Tools not offered to the agent, only to [extensions](#from-an-extension) |

## Hosted servers

A server reachable over HTTP needs a URL instead of a command, and usually an
API key. The key goes in a file outside the config:

```clojure
;; ~/.config/xi/mcp.edn
{:render {:transport :http
          :url       "https://mcp.render.com/mcp"
          :auth      {:ext-config "render" :key "RENDER_API_KEY"
                      :header "Authorization" :scheme "Bearer"}}}
```

```text
# ~/.config/xi/ext/render.env
RENDER_API_KEY=rnd_your_key_here
```

Xi reads the key when it connects and sends it as `Authorization: Bearer …`.
A hosted server's code is not on your disk, so trusting it covers its entry
only. Literal headers go under `:headers`. A hosted server that assigns a
session id on the first request gets it echoed on every later one.

## How a server runs

- A stdio server is started on its first call and shared by every chat.
  When it exits, the next call starts a new one. `/mcp disable` and quitting
  Xi stop it; a well-behaved server also exits when its input closes.
- Each call carries the calling chat's context in the request's `_meta`:
  `xi/cwd` (the working directory), `xi/roomId`, `xi/clientPid` (the
  terminal driving the chat, when there is one) and `xi/extension` (when an
  extension, not the agent, is calling). A server can act on these without
  a tool argument the model would have to fill in.
- A call that takes longer than `:timeout-ms` fails instead of hanging the
  turn.

## From an extension

An [extension](extensions.md) can call a registered server's tools itself,
to share something stateful like a browser:

```clojure
(mcp/call ctx :chrome "list_pages" {})
```

An extension can also bring a server of its own that only it can use. See
[Tutorial: your own MCP server](extension-tutorial-mcp.md).

## Writing a server

An MCP server is a program that reads JSON-RPC requests from stdin, one per
line, and answers on stdout. The conventions that matter:

- stdout carries protocol only; log to stderr.
- Answer `initialize` (with `protocolVersion`, `capabilities {tools {}}`,
  `serverInfo`), `ping`, `tools/list` and `tools/call`; send no reply to
  `notifications/*`; answer unknown methods with JSON-RPC error `-32601`.
- A tool that fails returns a result with `isError: true`, so the model sees
  why. JSON-RPC errors are for protocol faults.
- Read context from `_meta`, not from extra tool arguments.

Xi ships two small, dependency-free servers to start from: a ClojureScript
one in `src/xi/mcp/server.cljs` and a Babashka one in
`packages/mcp-bb-example/`. Register the Babashka one with:

```clojure
{:bb-example {:command "bb"
              :args ["--config" "/path/to/xi/packages/mcp-bb-example/bb.edn"
                     "-m" "hello-mcp.main"]
              :code-paths ["/path/to/xi/packages/mcp-bb-example/src"]}}
```

## When something is off

**`/mcp add` hangs or fails.** The server did not answer the first handshake.
Run its command by hand in a terminal; most servers print why they could not
start. A missing `npx` or an unpinned package that failed to download are the
usual causes.

**The tools are listed but every call asks.** The server is not trusted yet.
Answer `a` once, or `/mcp trust <id>`.

**A trusted server asks again.** Its code changed: the package was updated, or
a file under `:code-paths` was edited. That is the point of the fingerprint;
trust it again.

**The tool list is stale after updating the server.** `/mcp refresh <id>`.

## Rules for MCP tools

A call to a server is a rules request `{:tool :mcp :mcp-server "id"
:mcp-tool "name"}`. The default that asks for untrusted servers is the
`mcp-confirm` bundle; your own rules can single out a server or a tool, see
the [rules reference](rules-reference.md).
