# Agent blocks

The agent can answer with a block instead of a wall of text: a checklist you
tick off, a table you sort, a chart, a form you fill in. A block appears in
the chat right after the tool call that made it, the agent can change it
while it works, and it can read what you did with it.

```text
show the failing tests as a checklist, I'll tick the ones I've looked at
```

## What a block looks like

Every block sits in a frame Xi draws, never the agent: a dashed border, a
header with **Agent block**, the kind (`checklist`, `table`, …), its title,
and where it came from (`ui_block` for the agent, an extension or MCP server
by name). The `≡` menu in the header shows the block's data and copies it as
text or JSON.

A block can only change itself. Its buttons and fields record what you did on
the block; they cannot approve a tool call, run a command, open a page or
send anything without a click. Xi never asks for permission inside an agent
block: permission questions appear on the tool call they belong to.

## Kinds

| Kind | Shows | You can |
| --- | --- | --- |
| `checklist` | Items with a checkbox | Tick items |
| `table` | Columns and rows | Sort by a column (click its header) |
| `keyvalue` | Labels and values | |
| `steps` | Steps marked todo, running, done, failed or skipped | |
| `progress` | A progress bar | |
| `callout` | A highlighted note: info, warn, success or error | |
| `chart` | A bar, line or sparkline chart; hover a bar or point for its value | |
| `choice` | Options to pick, one or several | Pick, then **Send** |
| `form` | Text, text area, select, checkbox and number fields | Fill in, then submit |
| `cards` | Cards with a title, text and buttons | Press a button |
| `hiccup` | A layout the agent composes from a fixed set of elements and Xi's own components | Press its buttons, use its fields |

The agent picks the kind. Asking for one by name works ("as a table", "give
me a form for the issue").

## Answering the agent

What you do on a block is saved on the block. The agent reads it with
`ui_state` when it decides to, or when you tell it to ("I ticked the ones
that are done"). The header shows **n new** while the agent hasn't read your
latest actions.

**Send** on a choice, submit on a form and buttons the agent marked to send
post a message to the chat for you, like typing it:

```text
<block-action id="b-3f9a" kind="choice" action="submit">
(x) Composite index — fast, one migration
( ) Materialized view — faster, needs a refresh job
</block-action>
```

While the agent is busy the message waits in the queue, as a typed prompt
would. Several people in the same chat see the same ticks and selections;
each action is recorded with who did it.

## In the terminal and in prompt mode

The terminal client shows a block's text under its tool call, and redraws it
when the agent updates the block:

```text
▣ Release 2.4 (checklist b-3f9a)
- [x] Bump the version
- [ ] Tag and push — after CI is green
```

Choices, forms and buttons are read-only there; use the web client to act on
them. [`xi prompt`](prompt.md) prints the same text where the block appears.

## Tools

| Tool | Does |
| --- | --- |
| `ui_block` | Show a block: `kind`, `title`, the kind's `data` (or `hiccup` for the `hiccup` kind) and an optional `id`. The agent gets a one-line summary back, not the block. |
| `ui_update` | Replace a block's data and/or title. Your ticks, picks and field values stay where their items still exist. |
| `ui_state` | A block's current state and the actions since the agent last read it; without an `id`, the chat's blocks and their unread actions. |

None of them asks. See [Built-in tools](builtin-tools.md#blocks).

## Blocks from extensions

An [extension](extensions.md) can add kinds of its own, named after it
(`deploys/board`): the agent finds them in `ui_block`'s list, and the
extension can show blocks itself, without the model. Their frame names the
extension as the source. See `:block-kinds` and `xi.api.blocks` in the
[extension reference](extensions-reference.md). The bundled `deploys` demo
extension is an example: enable it with `:demo-extensions ["deploys.cljs"]`
in `config.edn` and run `/deploys`.

## Limits

| Limit | Value |
| --- | --- |
| Blocks per chat | 64 |
| Size of one block's data | 32 KB |
| Table cells | 10 000 |
| Elements in a `hiccup` block | 2 000 |
| Actions a block remembers | the last 50 |

Beyond a limit the tool fails and tells the agent which one. Blocks are saved
with the chat and come back when you resume it or the server restarts.
