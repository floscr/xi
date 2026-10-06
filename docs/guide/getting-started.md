# Getting started

Install Xi, run it in a project, and open the same chat in your browser. Ten
minutes, one package.

## What you need

- **Bun** 1.3 or newer. Xi runs on Bun, not Node. Install it from
  [bun.sh](https://bun.sh).
- **A Claude account or an API key.** Xi talks to Claude through the Claude
  Agent SDK. If you have signed in to Claude Code on this machine, Xi uses that
  login. Otherwise set `ANTHROPIC_API_KEY` in your shell.
- A terminal that can show colours. Any modern one does.

## Install

```sh
bun install -g xi-agent
```

The package installs one command, `xi`. Check it works:

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

You get a full-screen chat in the terminal. Type what you want done and press
Enter. Xi reads the project, calls tools (reading files, editing, running
commands) and shows each call as a block you can expand. When a call needs
your approval, a dialog asks; press `y` or `n`. Press `Esc` to stop a running
turn, and type `/quit` to leave.

Everything is saved as you go. The next time you run `xi` in the same
directory, `/resume` lists your previous chats.

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

## A project's instructions

If the project has an `AGENTS.md` (or `CLAUDE.md`) file, Xi reads it into the
system prompt at the start of every chat. That is where you tell the agent how
the project is built and tested, what to avoid, and where things are. Files in
parent directories load too, so a `~/code/AGENTS.md` applies to every project
below it.

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

## When something is off

**`xi: command not found` after installing.** npm's global bin directory is
not on your `PATH`. `bun pm bin -g` prints it; add that directory to your
shell's `PATH`.

**"Bun is required".** The launcher found Node but no Bun. Install Bun and
make sure `bun` is on your `PATH`.

**The first message fails with an authentication error.** Xi found neither a
Claude Code login nor `ANTHROPIC_API_KEY`. Sign in with the Claude CLI (`claude`,
then `/login`), or export the key and start Xi again.

**The browser shows "Connecting…" forever.** The server is not running, or you
opened the page on a different port. `xi server` prints the URL it serves.

## Next

[How Xi works](concepts.md) explains the few ideas the rest of the guide builds
on. If you would rather start doing things, go straight to
[Projects](projects.md) or [Extensions](extensions.md).
