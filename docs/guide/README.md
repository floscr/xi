# Xi user guide

This directory is the **source of the Xi documentation site**. Everything here
is plain Markdown that reads well on its own (in an editor, on GitHub, in a
terminal). The site in [`site/`](../../site/) renders it: `bb site:dev` from
the repo root serves it live, `bb site:build` writes it to `site/dist/`.

It is written for people who use Xi. It is not the contributor / agent
reference — that is [`docs/`](..), which stays as it is.

## How this differs from `docs/`

| | `docs/*.md` (reference) | `docs/guide/` (this guide) |
| --- | --- | --- |
| Reader | Contributors and agents working in the repo | Someone installing and using Xi |
| Shape | Exhaustive: every key, event, flag | Task-first: "how do I …", then why |
| Tone | Terse, internal names allowed | Plain language; internal names only when the reader needs them |
| Indexed from | `AGENTS.md` | This file |
| Source of truth for | Option tables, event/field lists, protocol details | Explanations, walkthroughs, examples |

**Don't duplicate the reference.** A guide page explains and shows the common
path, then links to the reference page for the full list of options. If an
option's behaviour changes, the reference is updated (as `AGENTS.md` already
requires) and the guide only changes if the explanation does.

## Writing rules

1. **One topic per file.** A page answers one question; if you need a second
   heading level to change subject, it is two pages.
2. **Start with what the reader gets**, in one or two sentences, before any
   detail. Then the shortest working example. Then the options.
3. **Examples must work** as written on a clean machine. Use `~` and
   placeholder names (`my-app`), never personal paths, hosts or tools. Show
   config as complete, copy-pasteable snippets.
4. **Say what happens when it goes wrong** — the error you will see and the
   fix — not only the happy path.
5. **Plain CommonMark + GFM tables.** No generator-specific syntax, no raw
   HTML, no includes. Fenced code blocks always carry a language.
6. **Relative links only**, to other `.md` files (`projects.md#remembered-projects`).
   Reference pages are linked as `../config.md#projects`; the site turns those
   into links to the repository.
7. **First line is the page title** (`# Title`), followed by a one-paragraph
   summary. That is all the metadata a page has; the site uses the summary
   on the docs overview.
8. **Images** live in `img/` next to the pages that use them, with alt text.
   Prefer a short code block over a screenshot when either would do.
9. **No dates, no "new in", no status.** Pages describe how Xi works now.
   History belongs in git.
10. **Say what a thing does, not how good it is.** No "powerful", "seamless",
    "intelligent". If a sentence would still be true with the adjective
    removed, remove it.

## Layout

Flat: one directory of `.md` files plus `img/`. Reading order is the list
under [Pages](#pages) below — **the site takes its navigation from that list**,
so a page that is not listed is not published. Section titles are the bold
lines; each page line is `- [Title](file.md) — one-line hook`.

## Pages

**Start here**
- [Getting started](getting-started.md) — install, first run in the terminal and the browser, sign in
- [How Xi works](concepts.md) — rooms, sessions, tools, rules and extensions in one page

**Everyday use**
- [Projects](projects.md) — how Xi finds your projects, per-project prompt and snippets
- [The web client](web-client.md) — the same session on any device, pairing, offline, keyboard
- [Slash commands](commands.md) — the commands you type into the chat

**Making it yours**
- [Configuration](configuration.md) — `config.edn`: where it lives, what goes in it, how it fails
- [Permissions and rules](rules.md) — what Xi may do on its own, and how to change it
- [The clj tool](clj-tool.md) — how the agent runs commands, and how you approve them
- [MCP servers](mcp-servers.md) — give the agent tools from any MCP server
- [Extensions](extensions.md) — add tools, commands, keys and pages with one file
- [Tutorial: a tool](extension-tutorial-tool.md) — your first extension, step by step
- [Tutorial: a command and a key](extension-tutorial-command.md) — react to events, run a command, show a badge
- [Tutorial: a web tool](extension-tutorial-http.md) — call an HTTP API from a tool
- [Tutorial: a page in the browser](extension-tutorial-web.md) — give your extension a web page
- [Tutorial: your own MCP server](extension-tutorial-mcp.md) — an extension that brings a headless browser
- [Extension reference](extensions-reference.md) — every key, API function and limit
- [Agent profiles](agents.md) — run Xi as an assistant with a fixed set of tools

**Running it**
- [Server mode](server.md) — one server, many clients, from any device
- [One-shot prompts](prompt.md) — `xi prompt` from scripts and other programs
