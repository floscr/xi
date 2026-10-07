# Xi user guide

This directory is the **source of the Xi documentation site**. Everything here
is plain Markdown that reads well on its own (in an editor, on GitHub, in a
terminal). The site in [`site/`](../../site/) renders it: `bb site:dev` from
the repo root serves it live, `bb site:build` writes it to `site/dist/`.

It is **the** documentation for people who use Xi: every option, flag,
command and rule a user can set is documented here and nowhere else. The
files in [`docs/`](..) are for contributors and agents working in the repo:
how things are built, not how to use them.

## How this differs from `docs/`

| | `docs/*.md` (internals) | `docs/guide/` (this guide) |
| --- | --- | --- |
| Reader | Contributors and agents working in the repo | Someone installing and using Xi |
| Content | Architecture, design decisions, build and test setup, wire formats | Everything a user can do or configure |
| Tone | Terse, internal names allowed | Plain language; internal names only when the reader needs them |
| Indexed from | `AGENTS.md` | This file |

**A user-facing change is documented here.** A new option, flag, command or
rule field gets its row in the matching reference page (`configuration.md`,
`command-line.md`, `rules-reference.md`, `clj-tool-reference.md`,
`extensions-reference.md`). An introduction page changes only if the
explanation does. Internals that help someone change the code go in
`docs/`.

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
   Internals pages are linked as `../architecture.md`; the site turns those
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
- [Project instructions](project-instructions.md) — `AGENTS.md`: which files load, and how the agent learns about the ones in subdirectories
- [How the agent reads code](reading-code.md) — large files arrive as a syntax-tree outline; the agent asks for the code it needs
- [Sessions](sessions.md) — resume, go back with `/tree`, summarise, trim and roll over
- [The web client](web-client.md) — the same session on any device, pairing, offline
- [Keyboard shortcuts](keyboard.md) — every key on both clients, the layers they apply in, and how to change them
- [Slash commands](commands.md) — the commands you type into the chat
- [Command line](command-line.md) — every subcommand and flag of `xi`

**Making it yours**
- [Configuration](configuration.md) — `config.edn`, the other files, environment variables
- [Models](models.md) — Claude, Ollama, OpenCode Zen, OpenAI Codex
- [Permissions and rules](rules.md) — what Xi may do on its own, and how to change it
- [Rules reference](rules-reference.md) — every match field, action and default bundle
- [The clj tool](clj-tool.md) — how the agent runs commands, and how you approve them
- [clj tool reference](clj-tool-reference.md) — every helper, gate and option
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
- [HTTPS](https.md) — a certificate for the web client, and trusting it on a phone
- [One-shot prompts](prompt.md) — `xi prompt` from scripts and other programs
- [Babashka client](babashka-client.md) — call Xi from Babashka and JVM programs
