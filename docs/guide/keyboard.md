# Keyboard shortcuts

Every key Xi reacts to runs a named action, and every key can be changed.
The terminal client and the web client share one model: a set of **actions**
(`:chat/new`, `:agent/abort`, `:diff/fold`, …), **layers** that say where a
key applies (everywhere, while navigating, in a diff view, …), and a keymap
that binds keys to actions per layer. The built-in keymap is below; your own
bindings go under `:keys` in `~/.config/xi/config.edn`.

Press `?` while not typing (or `Alt+/` anywhere) to see the shortcuts that
apply right now. In the terminal the same opens a scrollable list; the
command palette has a "Keyboard shortcuts" entry on both. In the web
palette (`Ctrl`/`Cmd+K`), a row that does the same as a bound key shows
that key on its right (`Find file… Ctrl+P`, `Prune all Alt+Shift+P`), and
follows your `:keys` overrides.

## Two modes

- **Compose**: a text field has focus — the message box, a search field, the
  terminal's editor. Keys type. Only shortcuts with `Ctrl`, `Alt` or `Cmd`
  (and named keys such as `Esc`) can fire.
- **Navigate**: nothing is focused, or a viewer (diff, file, the shortcuts
  list) has focus. Single keys work, such as `?`.

In the web client, `Esc` leaves the text field. There is no built-in key back
into it; bind `:compose/focus` in `:mode/navigate` (`i` in the example under
[Vim-style keys](#vim-style-keys)), or click the box. In the terminal the editor is always in compose mode; opening a diff or
file (`/diff`, a file name in the chat) switches to a navigating viewer, and
`q` or `Esc` brings the editor back.

## Layers

A key is looked up from the most specific layer to the most general; the
first layer that mentions it wins.

| Layer | Applies |
| --- | --- |
| `:permission-pending` | While a permission request is waiting for an answer |
| `:agent-busy` | While the agent is working |
| `:buffer/diff`, `:buffer/file`, `:buffer/prompt`, `:buffer/keys` | While that view is open (diff, file, system prompt, this shortcut list) |
| `:buffer/pager` | Any scrollable viewer in the terminal (under the specific `:buffer/…` layer) |
| `:page/chat`, `:page/home`, `:page/git-status` | The web client's pages |
| `:mode/compose`, `:mode/navigate` | The two modes above |
| `:global` | Everywhere |

## Default keys

Both clients:

| Key | Does | Action | Layer |
| --- | --- | --- | --- |
| `Alt+n` | New chat in the same directory | `:chat/new` | `:global` |
| `Alt+/` | Show the keyboard shortcuts | `:keys/show` | `:global` |
| `Alt+x` | Stop the running turn | `:agent/abort` | `:agent-busy` |

Web client:

| Key | Does | Action | Layer |
| --- | --- | --- | --- |
| `Alt+\` | Show / hide the sidebar | `:sidebar/toggle` | `:global` |
| `Alt+u` | Jump to the chat that needs you most: one waiting on a permission request, then the newest finished chat with unread output, then the newest running one. Press again to move on | `:session/jump-attention` | `:global` |
| `Alt+j` / `Alt+k` | Next / previous chat | `:session/next` / `:session/prev` | `:global` |
| `Alt+Shift+P` | Prune: run every cleanup the sidebar's "Prune all" would | `:sessions/prune` | `:global` |
| `Ctrl+p` / `Cmd+p` | Find a file | `:files/find` | `:global` |
| `Alt+b` | Switch buffer: the chat's open diffs and files as a filterable list, Enter opens one | `:buffers/switch` | `:global` |
| `Esc` | Close the open dialog (Appearance, shortcuts) | `:dialog/close` | `:global` |
| `Alt+a` / `Alt+d` | Allow / deny the pending permission request | `:permission/allow` / `:permission/deny` | `:permission-pending` |
| `Alt+s` | Always allow requests like the pending one (only when it offers that choice) | `:permission/always` | `:permission-pending` |
| `Alt+Shift+A` | Allow repo writes for the pending request (only when it offers that choice) | `:permission/allow-repo` | `:permission-pending` |
| `Alt+Shift+B` | Allow the pending request and the rest of its tool call's requests (only when it offers that choice) | `:permission/allow-block` | `:permission-pending` |
| `?` | Show the keyboard shortcuts | `:keys/show` | `:mode/navigate` |
| `Esc` | Leave the text field | `:compose/blur` | `:mode/compose` |
| `q`, `Esc` | Leave the diff or file view, back to the chat (the buffer stays open) | `:buffer/close` | `:buffer/diff`, `:buffer/file` |
| `] f` / `[ f` | Next / previous file in the diff | `:diff/next-file` / `:diff/prev-file` | `:buffer/diff` |

Terminal client, in any viewer (`:buffer/pager`):

| Key | Does | Action |
| --- | --- | --- |
| `j` / `k`, `↓` / `↑` | Move the cursor | `:pager/down` / `:pager/up` |
| `g g` / `G` | Top / bottom | `:pager/top` / `:pager/bottom` |
| `Ctrl+d` / `Ctrl+u` | Half a page down / up | `:pager/half-down` / `:pager/half-up` |
| `PgDn` / `PgUp` | A page down / up | `:pager/page-down` / `:pager/page-up` |
| `V` | Start or cancel a line selection | `:pager/select` |
| `y` | Copy the line or selection | `:pager/yank` |
| `e` | Ask the agent to explain the selection | `:pager/explain` |
| `Enter` | Put the selection in the editor | `:pager/prompt` |
| `] c` / `[ c` | Next / previous change | `:pager/next-change` / `:pager/prev-change` |
| `] f` / `[ f` | Next / previous file | `:diff/next-file` / `:diff/prev-file` |
| `:` | Command mode: focus the editor | `:pager/command` |
| `q`, `Esc` | Close (Esc cancels a selection first) | `:pager/close` |
| `?` | Show the keyboard shortcuts | `:keys/show` |

Terminal client, more:

| Key | Does | Action | Layer |
| --- | --- | --- | --- |
| `v` | Open the file under the cursor in `$EDITOR` | `:diff/edit` | `:buffer/diff` |
| `Tab` | Fold / unfold the file under the cursor | `:diff/fold` | `:buffer/diff` |
| `Ctrl+o` | System prompt: full / overview | `:prompt/toggle` | `:buffer/prompt` |

Extensions add their own: an extension's `:keybindings` entry is an action
too (its id is the event type unless the extension names one), with the key
it asks for as the default. The built-in file finder (`Ctrl+p`,
`:file-finder/open`) and project picker (`Alt+p`, `:project/open-picker`) are
such actions.

Some keys are part of a control rather than the keymap and cannot be changed
here: `Ctrl+k` / `Cmd+k` opens the command palette, `Tab` in the message box
expands a snippet, the `/` command list moves with `↑`/`↓`, `Alt+k`/`Alt+j`
or `Ctrl+p`/`Ctrl+n`, `Ctrl+Enter` on one of your messages edits it, and the
terminal editor's own editing keys (`Ctrl+a`, `Ctrl+k`, `Ctrl+/`, …).

## Changing keys

```clojure title="~/.config/xi/config.edn"
{:type    :xi/config
 :version 1

 :keys {:global        {"alt+n"        nil            ; unbind
                        "ctrl+shift+n" :chat/new}     ; bind
        :mode/navigate {"?"            nil}
        :buffer/diff   {"x"            :diff/fold}    ; add a key in one view
        :web           {:global {"alt+\\" nil}}       ; web client only
        :tui           {:buffer/pager {"J" :pager/half-down
                                       "K" :pager/half-up}}}}
```

| Key | Does |
| --- | --- |
| `<layer> {"key" :action}` | Bind a key in that layer, on both clients. `nil` removes the key (yours or a default). |
| `:web {…}` / `:tui {…}` | The same, for one client only. Applied after the shared entries. |
| `:defaults? false` | Start from nothing: no built-in keys. At the top level for both clients, or inside `:web` / `:tui` for one. |

Keys are read when a client starts: restart the terminal client, reload the
web page.

### Writing a key

- Modifiers come first, joined with `+`: `ctrl`, `alt`, `shift`, `meta`
  (also `cmd`). `mod` means `ctrl` in the terminal and `ctrl` or `cmd` in the
  browser.
- Then one character or a named key: `escape` (`esc`), `enter`, `tab`,
  `backspace`, `delete`, `space`, `up`, `down`, `left`, `right`, `home`,
  `end`, `pageup`, `pagedown`, `insert`, `f1` to `f12`.
- A bare uppercase letter means shift: `"G"` is `"shift+g"`. Next to a
  modifier case does not matter: `"alt+N"` is `"alt+n"`.
- A sequence of keys is written with spaces, `"g g"`, `"] c"`, or as a
  vector, `["g" "g"]`.
- With a modifier held, name the unshifted key: `"alt+shift+/"`, not `"alt+?"`.

### Vim-style keys

The web client binds no single-letter navigation by default. These actions
are available for `:mode/navigate`:

| Action | Does |
| --- | --- |
| `:compose/focus` | Focus the message box |
| `:scroll/down` / `:scroll/up` | Scroll the visible view (timeline, diff, file, projects page, shortcut list) |
| `:scroll/half-down` / `:scroll/half-up` | The same, half the view's height at a time |
| `:timeline/bottom` | Scroll to the bottom |
| `:prompt/prev` / `:prompt/next` | Jump between your messages in the chat. The first `:prompt/prev` lands on the newest; `:prompt/next` on the last goes back to the bottom |
| `:diff/next-hunk` / `:diff/prev-hunk` | Next / previous hunk in a diff |

```clojure title="~/.config/xi/config.edn"
:keys {:web {:mode/navigate  {"i" :compose/focus
                              "j" :scroll/down
                              "k" :scroll/up
                              "d" :scroll/half-down
                              "u" :scroll/half-up
                              "G" :timeline/bottom
                              "[" :prompt/prev
                              "]" :prompt/next}
             :buffer/diff     {"[" :diff/prev-hunk
                               "]" :diff/next-hunk
                               "] f" nil          ; "]" alone would shadow these
                               "[ f" nil}
             :page/git-status {"[" :diff/prev-hunk
                               "]" :diff/next-hunk}}}
```

A diff tab in a chat is `:buffer/diff`, the git status page is
`:page/git-status`; both sit above `:mode/navigate`, so `[` and `]` jump
hunks there and messages in the chat.

### Leader keys

A sequence starting with `space` in `:mode/navigate` is a leader key, as in
Doom Emacs' normal mode. Web client only; each key must follow within 1.5
seconds.

A binding's value is an action, or a string: the text to send in the current
chat, so a slash command runs as if you had typed it. A string binding does
nothing outside a chat.

```clojure title="~/.config/xi/config.edn"
:keys {:web {:mode/navigate {["space" "space"]  :palette/open
                             ["space" "f" "f"]  :files/find
                             ["space" "p" "p"]  :projects/pick
                             ["space" "b" "b"]  :buffers/switch
                             ["space" "g" "g"]  :git/status
                             ["space" "g" "c"]  "/commit"
                             ["space" "c" "n"]  :chat/new
                             ["space" "c" "u"]  :session/jump-attention
                             ["space" "c" "x"]  :chat/delete
                             ["space" "c" "h"]  :chat/hide}}}
```

| Action | Does |
| --- | --- |
| `:palette/open` | The `Ctrl`/`Cmd+K` palette; its first group lists your chats |
| `:projects/pick` | The palette's project list; Enter on one opens its actions (sessions, new chat, …) |
| `:projects/open` | The projects page |
| `:git/status` | The working-tree diff of the current chat |
| `:chat/hide` | Hide the current chat from Recent, or show it again |
| `:chat/delete` | Delete the current chat, no confirmation |

`:chat/hide` and `:chat/delete` only run on a chat page.
