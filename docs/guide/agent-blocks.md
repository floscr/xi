# Agent blocks

The agent can put something in the chat that you act on: a checklist you
tick off, options to pick, a layout with buttons and fields. A block
appears right after the tool call that made it, the agent can change it while
it works, and it can read what you did with it. Tables, lists and notes you
only read come as ordinary text in the agent's answer.

```text
show the failing tests as a checklist, I'll tick the ones I've looked at
```

## What a block looks like

Every block sits in a frame Xi draws, never the agent: a dashed border in
the theme's accent color, a header with **Agent block**, the kind
(`checklist`, `choice`, …), its title, and where it came from (`ui_block` for
the agent, an extension or MCP server by name). The `≡` menu in the header
opens the block as a [buffer](web-client.md#buffers) of the chat, shows its
data and copies it as text or JSON.

A block can only change itself. Its buttons and fields record what you did on
the block; they cannot approve a tool call, run a command, open a page or
send anything without a click. Xi never asks for permission inside an agent
block: permission questions appear on the tool call they belong to.

## Kinds

| Kind | Shows | You can |
| --- | --- | --- |
| `checklist` | Items with a checkbox | Tick items |
| `choice` | Options to pick, one or several | Pick, then **Send** |
| `hiccup` | A layout the agent composes from a fixed set of elements and Xi's own components, for what text can't show: badges, a progress bar, an SVG chart | Press its buttons, use its fields |
| `dial` | A tuning panel: sliders, toggles, color pickers, text, selects, a 2-D pad, spring and easing curves, buttons, in folders | Drag and pick values; its buttons can post a message |

The agent picks the kind. Asking for one works ("as a checklist", "let me
pick one", "give me sliders for it").

A `dial` keeps what you set: the agent reads the current values with
`ui_state`, and a whole slider drag counts as one action. Once the tuning is
done the agent can lock it, which leaves the values as a plain list.

## Answering the agent

What you do on a block is saved on the block. The agent reads it with
`ui_state` when it decides to, or when you tell it to ("I ticked the ones
that are done"). The header shows **n new** while the agent hasn't read your
latest actions.

**Send** on a choice and buttons the agent marked to send post a message to
the chat for you, like typing it:

```text
<block-action id="b-3f9a" kind="choice" action="submit">
(x) Composite index — fast, one migration
( ) Materialized view — faster, needs a refresh job
</block-action>
```

From the keyboard, `:block/next` / `:block/prev` jump between blocks and the
arrow keys move inside one; see
[Keyboard navigation](keyboard-navigation.md#agent-blocks).

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

## App views

A tool can come with an interactive view of its own, an
[MCP App](mcp-servers.md#apps), from an MCP server or an extension. The web
client shows it as a block of kind `app` after the tool ran: the view's page
in a sandboxed frame. Its frame is solid where an agent block's is dashed,
and its header says **External app**, so a page from outside Xi never passes
for Xi's own UI or for the agent's blocks.

The view runs isolated from Xi: it cannot read the chat, your login or other
pages, and it reaches the network only where its server declared it may
(nothing by default). What it can do goes through Xi:

| The view asks to | What happens |
| --- | --- |
| Call a tool | Only tools of its own server or extension, through the [rules](rules.md) like the agent's call. The agent sees each call with `ui_state`. |
| Read a resource | Only from its own server. |
| Send a message | The text goes into the message box; you send it or not. |
| Open a link | Xi asks first. |
| Go full screen | The view fills the chat; **Exit full screen** in the header returns. |
| Update the agent's context | Kept on the block; the agent reads it with `ui_state`. |

The terminal client and `xi prompt` show the tool's text result instead.

Two bundled [demo extensions](extensions.md#demo-extensions) have app
views: `deploys.cljs` (`deploy_map`, a clickable environment list) and
`scene.cljs` (`scene_show`, a three.js scene that loads three.js from
jsDelivr).

## Limits

| Limit | Value |
| --- | --- |
| Blocks per chat | 64 |
| Size of one block's data | 32 KB |
| Elements in a `hiccup` block | 2 000 |
| Actions a block remembers | the last 50 |

Beyond a limit the tool fails and tells the agent which one. Blocks are saved
with the chat and come back when you resume it or the server restarts.
