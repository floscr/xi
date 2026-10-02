# Xi user guide

This directory is the **source of the Xi documentation site**. Everything here
is plain Markdown that reads well on its own (in an editor, on GitHub, in a
terminal). At some point a build step will turn it into the docs page; until
then the files *are* the docs.

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
   Reference pages are linked as `../config.md#projects`.
7. **First line is the page title** (`# Title`), followed by a one-sentence
   summary. That is all the metadata a page has; the generator derives the rest.
8. **Images** live in `img/` next to the pages that use them, with alt text.
   Prefer a short code block over a screenshot when either would do.
9. **No dates, no "new in", no status.** Pages describe how Xi works now.
   History belongs in git.

## Layout

Flat for now: one directory of `.md` files plus `img/`. Reading order is the
list under [Pages](#pages) below — the generator will take navigation from
that list, so a page that is not listed is not published.

When a section grows past a handful of pages it gets its own subdirectory with
its own `README.md` as the section index.

## Pages

Planned, in reading order. A page is only linked once it exists; the first
pages to write are the ones the public release needs.

**Start here**
- *Getting started* — install, first run (TUI, server, web), sign in
- *Concepts* — rooms, sessions, projects, tools, rules in one page each

**Everyday use**
- [Projects](projects.md) — how Xi finds your projects: browse dirs, repos it
  remembers on its own, `/project`, per-project prompt and snippets
- *Sessions and resuming*
- *The web client*
- *Slash commands*

**Making it yours**
- *Configuration* — `config.edn`: where it lives, how it is validated, what
  to put in it
- *Permissions and rules* — what Xi may do on its own and how to change it
- *Extensions* — user extensions, MCP servers

**Running it**
- *Server mode* — `xi server`, pairing, TLS, running headless
- *`xi prompt`* — one-shot use from scripts

## Open decisions

- **Generator.** Not chosen. Constraint: it must build from these files as
  they are (rules 5–7), so any static-site tool that reads Markdown works.
  Pick it when there are enough pages to see.
- **Where the site is published and its URL.** Decided with the generator.
- **Versioning.** One version of the docs, tracking the main branch, until a
  release makes that a problem.
