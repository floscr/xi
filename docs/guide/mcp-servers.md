# MCP servers

Any MCP server can give the agent tools. Add it with one command, approve it
once, and its tools are there on the next turn, under the same rules as every
other tool.

## Add a server

```text
/mcp add context7 npx -y @upstash/context7-mcp@1.0.14
```

Xi starts the server once, asks it for its tools, writes them to a cache, and
registers them as `mcp__context7__<tool>`. From the next turn on, the agent
can call them. The server process itself is only started when a tool is
first called, so an unused server costs nothing.

`/mcp list` shows the servers you have, how many tools each offers, and
whether it is trusted.

```text
/mcp disable context7    # keep it registered, offer no tools
/mcp enable context7
/mcp refresh context7    # re-read its tool list after an update
/mcp remove context7
```

Pin a version in the command, as above. A server installed as `@latest`
changes under you, and Xi cannot see that it did.

## Trusting a server

A server you just added runs nothing yet. Its first tool call shows a dialog
with the server, the tool and every argument:

```text
MCP tool call — approve?

Server: context7
Tool:   get-library-docs

Arguments:
  context7CompatibleLibraryID: /facebook/react
  topic: hooks
```

`y` runs this call. `a` (**Always**) runs it and trusts the server: its calls
stop asking, in every chat, until the server's code changes. Xi records a
fingerprint of the server's entry and the files its command names; an edit or
a rebuild asks again. `/mcp trust <id>` does the same without waiting for a
call, `/mcp untrust <id>` takes it back.

To trust a server for good, name it in your [config](configuration.md):

```clojure
:trusted-mcp-servers ["context7"]
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
{:context7 {:transport :stdio
            :command   "npx"
            :args      ["-y" "@upstash/context7-mcp@1.0.14"]}

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
only.

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
line, and answers on stdout. Xi ships two small, dependency-free ones to
start from: a ClojureScript one in `src/xi/mcp/server.cljs` and a Babashka
one in `packages/mcp-bb-example/`. The reference page explains the handful of
methods a server has to answer.

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

## Reference

Registry keys, the trust fingerprint, the HTTP transport and the wire
protocol: [the MCP reference](../mcp-servers.md).
