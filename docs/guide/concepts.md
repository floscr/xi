# How Xi works

The handful of ideas the rest of this guide builds on: rooms and sessions,
tools, rules, extensions, and why the terminal and the browser show the same
thing.

## Rooms and sessions

A **session** is a saved chat: its messages, the directory it ran in, the
model it used. Sessions live on disk and can be resumed any time.

A **room** is a session that is currently open. Rooms live in a running Xi
process. When you type `xi`, you get one room. When you run `xi server`, the
server holds many rooms, and every client (terminal or browser) joins one of
them. Several clients can sit in the same room and see the same stream.

A room closes when nobody is in it and the agent is idle. A room whose agent
is still working stays open even with nobody watching, so you can start a
task, close your laptop, and find the result later.

## One state, every surface

Xi keeps the state of all its rooms in one place. Every change to it is an
**event**: a prompt you send, a token the model streams, a command you type, a
dialog that opens. The terminal client, the server and the browser client all
process the same events with the same code.

That is why there is no separate "web version". The server broadcasts each
event to the room's clients, and each client applies it exactly as the server
did. Standalone mode is the same thing with nobody to broadcast to.

## Tools

The model acts through **tools**: read a file, edit it, search, run a command,
fetch a page. Xi offers a fixed set of built-in tools, and you add more through
[extensions](extensions.md) and [MCP servers](mcp-servers.md).

Reading a large source file returns an outline of its syntax tree, not the
file; the agent asks for the definitions it needs. See
[How the agent reads code](reading-code.md).

One built-in tool stands out: instead of a shell, the agent gets a sandboxed
Clojure REPL called [`clj`](clj-tool.md). Real commands run through it one at
a time, argv-style, so you can read every one before it runs.

## Rules

Every tool call passes the **rules** before it runs. A rule matches a call
(by tool, path, command, repo, …) and says what to do: allow it, deny it, ask
you, or nudge the agent in another direction. Rules are plain data in a file
you own, evaluated top to bottom, first match wins. A set of built-in defaults
covers the common cases; a hardened tier covers the dangerous ones and cannot
be overridden.

The agent can never edit the rules files. See
[Permissions and rules](rules.md).

## Extensions

An **extension** is one ClojureScript file in `~/.config/xi/extensions/`. It
is a map that may add tools for the agent, slash commands for you, keyboard
shortcuts, text for the system prompt, or a page in the web client. Extensions
run in a sandbox: they have no access to your machine except through a small
API, and every call through that API passes the rules like an agent's tool
call does.

Xi's own features (plan mode, the diff viewer, `/commit`, MCP support, the
clj tool) are extensions built the same way. See [Extensions](extensions.md).

## MCP

MCP is the Model Context Protocol: a plain way for a program to offer tools
to a model. Xi uses it in both directions. It offers its own tools to the
Claude SDK over MCP, and it pulls tools in from any MCP server you add. An
added server's tools look to the agent like any other tool, and the rules
apply to them as well. See [MCP servers](mcp-servers.md).

## Projects

A **project** is a directory Xi knows about, so you can jump into it from the
chat and give it its own instructions and snippets. Xi finds projects in the
directories you tell it to scan and remembers the repositories you work in.
See [Projects](projects.md).

## Where to go next

- Daily use: [The web client](web-client.md), [Slash commands](commands.md)
- Making it yours: [Configuration](configuration.md), [Permissions and rules](rules.md)
- Extending it: [Extensions](extensions.md), then the tutorials
