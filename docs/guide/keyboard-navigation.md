# Keyboard navigation

Use the web client without a mouse: leave the message box, click any button
or sidebar row by typing its label, move between chats, and scroll a chat.
The keys that move between chats work out of the box; the single-letter
keys are yours to bind in `~/.config/xi/config.edn`.

A complete setup, explained in the sections below:

```clojure title="~/.config/xi/config.edn"
:keys {:web {:mode/navigate {"i"      :compose/focus
                             "f"      :hints/show
                             "F"      :hints/sidebar
                             "j"      :scroll/down
                             "k"      :scroll/up
                             "d"      :scroll/half-down
                             "u"      :scroll/half-up
                             "g g"    :scroll/top
                             "G"      :timeline/bottom
                             "["      :prompt/prev
                             "]"      :prompt/next
                             "{"      :answer/prev
                             "}"      :answer/next
                             "b"      :block/next
                             "B"      :block/prev
                             "ctrl+o" :nav/back
                             "ctrl+i" :nav/forward}}}
```

Reload the web page after changing keys.

## Typing and navigating

Single-letter keys only work while no text field has focus. `Esc` leaves the
message box; `:compose/focus` (`i` above) goes back into it. More on the
modes: [Keyboard shortcuts](keyboard.md#two-modes).

## Link hints

`:hints/show` (`f` above) puts a yellow label of one or two letters on every
button, link, text field and sidebar row you can click right now. Inside an
open dialog only the dialog's own get one.

- Type a label to click its element. A text field gets focus instead.
- `Shift` on the last letter opens a link in a new tab.
- `Backspace` takes a letter back.
- `Esc`, a click or the mouse wheel cancels.

`:hints/sidebar` (`F` above) labels only the sidebar, so the labels stay
short. When the sidebar is a closed drawer it opens first, and closes again
if you cancel.

The labels do not follow the page: if a running chat scrolls while they show,
press the key again.

## Moving between chats

The `:session/…` actions have [default keys](keyboard.md#default-keys);
the palette is `Ctrl`/`Cmd+k`.

- `:session/next` / `:session/prev` walk the sidebar rows. The
  `-sibling` variants stay in the same group; on the open chat's card,
  `:session/next-sibling` steps into its buffers.
- `:session/jump-attention` goes to the chat that needs you most. While
  permission requests are pending it cycles through them, oldest first; then
  unread output, then running turns, and stops after the last.
- The command palette's first group lists your chats.
- `:nav/back` and `:nav/forward` (`Ctrl+o` / `Ctrl+i` above) walk through
  the pages, chats and buffers you opened, like the browser's back and
  forward.

## Inside a chat

- `:scroll/down`, `:scroll/up` and the half-page variants scroll whatever
  view is in front: the chat, a diff, a file.
- `:scroll/top` shows every earlier message and scrolls to the first;
  `:timeline/bottom` back to the newest.
- `:prompt/prev` / `:prompt/next` jump between your own messages.
- `:answer/prev` / `:answer/next` jump between the agent's answers: the last
  text of each turn, skipping what it writes between tool calls. A running
  turn's answer counts once the turn ends. The answer you land on gets a bar
  on its left. From the bottom of the chat,
  `:answer/prev` goes to the start of the newest answer; past the oldest
  shown message it shows earlier ones.

## Agent blocks

`:block/next` / `:block/prev` (`b` / `B` above) jump to the next or previous
[agent block](agent-blocks.md) and put the focus on its first button or
field. Inside a block:

| Key | Does |
| --- | --- |
| Arrow keys | Move between the block's buttons and fields |
| `Home` / `End` | The first / last of them |
| `Enter` / `Space` | Press the button, tick the box |
| `Esc` | Leave the block, back to the navigate keys |

In a text field or a select the arrow keys stay with it; `Tab` moves on.

Hold `Alt` for a moment to see the keys on their buttons: the permission
buttons, Stop, New chat and the sidebar toggle show the key that runs them.
Allow's menu opens too, with the keys for Always and the other grants.

Every action, its default key and how to change it:
[Keyboard shortcuts](keyboard.md).
