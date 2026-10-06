# Getting started

Install Xi, run it in a project, and open the same chat in your browser. Ten
minutes, one package.

## What you need

- **Bun** 1.3 or newer. Xi runs on Bun, not Node. Install it from
  [bun.sh](https://bun.sh).
- **A Claude subscription or an API key.** Xi talks to Claude through the
  Claude Agent SDK, using either your Claude Code login or `ANTHROPIC_API_KEY`.

## Install

```sh
bun install -g xi-agent
```

The package installs one command, `xi`. Verify it works via:

```sh
xi help
```

To run from a checkout instead, see [From source](#from-source) below.

## First run

Go to a project and start Xi:

```sh
cd ~/code/my-app
xi
```

This gives you standalone Xi: a full-screen chat in your terminal, running on
its own and not connected to any server. Tell it what you want done and it
reads the project, calls tools (reading files, editing, running commands) and
asks for your approval when a call needs it.

## The browser

Xi can run as a server, and then the terminal and the browser share the same
chats:

```sh
xi server
```

This opens the terminal client as before and also serves the web client at
[http://localhost:7474](http://localhost:7474). Open it on the same machine
and you see the chat you just started, still streaming. Reply from either
side; the other follows.

To use it from your phone or another computer, the server has to be
reachable on your network, and the new device has to pair once. Both are
covered in [The web client](web-client.md) and [Server mode](server.md).

## AGENTS.md auto loading

If the project has an `AGENTS.md` (or `CLAUDE.md`) file, Xi reads it into the
system prompt at the start of every chat. That is where you tell the agent how
the project is built and tested, what to avoid, and where things are. Files in
parent directories load too, so a `~/code/AGENTS.md` applies to every project
below it. Other `AGENTS.md` files inside the repository are listed for the
agent to read when it works near them. See
[Project instructions](project-instructions.md).

## Where things live

| Path | What it is |
| --- | --- |
| `~/.config/xi/config.edn` | Your configuration. Optional; see [Configuration](configuration.md). |
| `~/.config/xi/rules.edn` | Your permission rules. Optional; see [Permissions and rules](rules.md). |
| `~/.config/xi/extensions/` | Your extensions. See [Extensions](extensions.md). |
| `~/.config/xi/sessions/` | Chat metadata. The transcripts themselves are kept by the Claude CLI under `~/.claude/projects/`. |

The agent can never write to `config.edn` or `rules.edn`: every tool refuses
to touch `~/.config/xi`. What you put there is yours.

## From source

```sh
git clone https://github.com/floscr/xi
cd xi
npm install
bb build        # the terminal client and server
bb web:build    # the web client
bin/xi
```

You need [Babashka](https://babashka.org) for the `bb` tasks. `bb tasks`
lists the rest.

## Next

[How Xi works](concepts.md) explains the few ideas the rest of the guide builds
on. If you would rather start doing things, go straight to
[Projects](projects.md) or [Extensions](extensions.md).
