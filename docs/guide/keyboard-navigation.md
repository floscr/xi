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

These keys are bound by default:

| Key | Does |
| --- | --- |
| `Alt+j` / `Alt+k` | Next / previous sidebar row |
| `Alt+Shift+J` / `Alt+Shift+K` | Next / previous row in the same sidebar group; on the open chat's card, into its buffers |
| `Alt+u` | The chat that needs you most: a pending permission request, then unread output, then a running turn |
| `Alt+\` | Show / hide the sidebar |
| `Ctrl/Cmd+k` | Command palette; its first group lists your chats |
| `Alt+b` | Switch between the chat's open diffs and files |

`:nav/back` and `:nav/forward` (`Ctrl+o` / `Ctrl+i` above) walk through the
pages, chats and buffers you opened, like the browser's back and forward.

## Inside a chat

- `:scroll/down`, `:scroll/up` and the half-page variants scroll whatever
  view is in front: the chat, a diff, a file.
- `:scroll/top` shows every earlier message and scrolls to the first;
  `:timeline/bottom` back to the newest.
- `:prompt/prev` / `:prompt/next` jump between your own messages.
- `Alt+a` / `Alt+d` allow or deny a permission request, `Alt+x` stops the
  turn.

Hold `Alt` for a moment to see these keys on their buttons: the permission
buttons, Stop, New chat and the sidebar toggle show the key that runs them.
Allow's menu opens too, with the keys for Always and the other grants.

Every action, its default key and how to change it:
[Keyboard shortcuts](keyboard.md).
