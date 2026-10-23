# Keyboard shortcuts

Every key Xi reacts to runs a named action, and every key can be changed.
The terminal client and the web client share one model: a set of **actions**
(`:chat/new`, `:agent/abort`, `:diff/fold`, …), **layers** that say where a
key applies (everywhere, while navigating, in a diff view, …), and a keymap
that binds keys to actions per layer. The built-in keymap is below; your own
bindings go under `:keys` in `~/.config/xi/config.edn`.

Press `?` while not typing (or `Alt+/` anywhere) to see the shortcuts that
apply right now. In the web client every key is a keycap, and a bar on the left
marks the bindings you changed. In the terminal the same opens a scrollable list; the
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
- **Terminal** (web): a [terminal](web-client.md#terminals) buffer has focus.
  Every key goes to its program, except those bound in `:mode/terminal`:
  `` Ctrl+` `` or `Alt+\` leaves the terminal, then the other keys work again.

In the web client, `Esc` leaves the text field. There is no built-in key back
into it; bind `:compose/focus` in `:mode/navigate` (`i` in the setup under
[Keyboard navigation](keyboard-navigation.md)), or click the box. In the terminal the editor is always in compose mode; opening a diff or
file (`/diff`, a file name in the chat) switches to a navigating viewer, and
`q` or `Esc` brings the editor back.

## Layers

A key is looked up from the most specific layer to the most general; the
first layer that mentions it wins.

| Layer | Applies |
| --- | --- |
| `:lightbox` | While an image is open full screen (web) |
| `:permission-pending` | While a permission request is waiting for an answer |
| `:agent-busy` | While the agent is working |
| `:buffer/diff`, `:buffer/file`, `:buffer/terminal`, `:buffer/prompt`, `:buffer/keys` | While that view is open (diff, file, terminal, system prompt, this shortcut list) |
| `:buffer/pager` | Any scrollable viewer in the terminal (under the specific `:buffer/…` layer) |
| `:page/chat`, `:page/home`, `:page/git-status` | The web client's pages |
| `:mode/compose`, `:mode/terminal`, `:mode/navigate` | The modes above. `:mode/terminal` takes single chords only |
| `:global` | Everywhere |

## Default keys

**Client** is the client that binds the key: both, web or terminal. The
permission keys other than `Alt+a` / `Alt+d` work only when the request
offers that choice.

<!-- gen:keys -->

**Everywhere** · `:global`

| Key | Does | Action | Client |
| --- | --- | --- | --- |
| `Alt+N` | New chat | `:chat/new` | both |
| `Alt+/` | Keyboard shortcuts | `:keys/show` | both |
| <code>Alt+&#92;</code> | Toggle the sidebar | `:sidebar/toggle` | web |
| `Alt+U` | Jump to the chat that needs you | `:session/jump-attention` | web |
| `Alt+↓` | Next sidebar item | `:session/next` | web |
| `Alt+↑` | Previous sidebar item | `:session/prev` | web |
| `Alt+Shift+↓` | Next sidebar item in the same group, or into the open chat's buffers | `:session/next-sibling` | web |
| `Alt+Shift+↑` | Previous sidebar item in the same group | `:session/prev-sibling` | web |
| `Alt+Shift+P` | Prune: run every sidebar cleanup | `:sessions/prune` | web |
| `Ctrl+P` / `Cmd+P` | Find a file | `:files/find` | web |
| `Esc` | Close the open dialog | `:dialog/close` | web |

**Navigating (no text field focused)** · `:mode/navigate`

| Key | Does | Action | Client |
| --- | --- | --- | --- |
| `?` | Keyboard shortcuts | `:keys/show` | web |

**In a text field** · `:mode/compose`

| Key | Does | Action | Client |
| --- | --- | --- | --- |
| `Esc` | Leave the text field | `:compose/blur` | web |

**Typing in a terminal** · `:mode/terminal`

| Key | Does | Action | Client |
| --- | --- | --- | --- |
| <code>Alt+&#92;</code> / <code>Ctrl+&#96;</code> | Leave the terminal, keys go back to Xi | `:terminal/leave` | web |

**Any buffer viewer** · `:buffer/pager`

| Key | Does | Action | Client |
| --- | --- | --- | --- |
| `?` | Keyboard shortcuts | `:keys/show` | terminal |
| `] f` | Next file | `:diff/next-file` | terminal |
| `[ f` | Previous file | `:diff/prev-file` | terminal |
| `j` / `↓` | Cursor down | `:pager/down` | terminal |
| `k` / `↑` | Cursor up | `:pager/up` | terminal |
| `g g` | Top | `:pager/top` | terminal |
| `G` | Bottom | `:pager/bottom` | terminal |
| `Ctrl+D` | Half page down | `:pager/half-down` | terminal |
| `Ctrl+U` | Half page up | `:pager/half-up` | terminal |
| `PgDn` | Page down | `:pager/page-down` | terminal |
| `PgUp` | Page up | `:pager/page-up` | terminal |
| `V` | Start / cancel line selection | `:pager/select` | terminal |
| `y` | Copy the line or selection | `:pager/yank` | terminal |
| `e` | Ask the agent to explain the selection | `:pager/explain` | terminal |
| `Enter` | Put the selection in the editor | `:pager/prompt` | terminal |
| `q` / `Esc` | Close (Esc cancels a selection first) | `:pager/close` | terminal |
| `:` | Command mode: focus the editor | `:pager/command` | terminal |
| `] c` | Next change | `:pager/next-change` | terminal |
| `[ c` | Previous change | `:pager/prev-change` | terminal |

**Diff view** · `:buffer/diff`

| Key | Does | Action | Client |
| --- | --- | --- | --- |
| `Esc` | Close this view, back to the chat | `:buffer/close` | web |
| `Enter` | Ask a sub-agent about the selected lines | `:diff/ask-selection` | web |
| `m` | Have a sub-agent modify the selected lines | `:diff/modify-selection` | web |
| `e` | Have a sub-agent explain the selected lines | `:diff/explain-selection` | web |
| `v` | Open the file under the cursor in $EDITOR | `:diff/edit` | terminal |
| `Tab` | Fold / unfold the file under the cursor | `:diff/fold` | terminal |

**File view** · `:buffer/file`

| Key | Does | Action | Client |
| --- | --- | --- | --- |
| `Esc` | Close this view, back to the chat | `:buffer/close` | web |

**Terminal view** · `:buffer/terminal`

| Key | Does | Action | Client |
| --- | --- | --- | --- |
| `Esc` | Close this view, back to the chat | `:buffer/close` | web |
| `Enter` | Type in the terminal | `:terminal/focus` | web |

**System prompt view** · `:buffer/prompt`

| Key | Does | Action | Client |
| --- | --- | --- | --- |
| `Ctrl+O` | System prompt: full / overview | `:prompt/toggle` | terminal |

**While the agent is working** · `:agent-busy`

| Key | Does | Action | Client |
| --- | --- | --- | --- |
| `Alt+X` | Stop the running turn | `:agent/abort` | both |

**While a permission request is pending** · `:permission-pending`

| Key | Does | Action | Client |
| --- | --- | --- | --- |
| `Alt+A` | Allow the pending permission request | `:permission/allow` | web |
| `Alt+S` | Always allow requests like the pending one | `:permission/always` | web |
| `Alt+Shift+A` | Allow repo writes for the pending permission request | `:permission/allow-repo` | web |
| `Alt+Shift+B` | Allow every request of the pending permission's block | `:permission/allow-block` | web |
| `Alt+D` | Deny the pending permission request | `:permission/deny` | web |

**While an image is open full screen** · `:lightbox`

| Key | Does | Action | Client |
| --- | --- | --- | --- |
| `Esc` | Close the image viewer | `:lightbox/close` | web |

<!-- /gen:keys -->

Extensions add their own: an extension's `:keybindings` entry is an action
too (its id is the event type unless the extension names one), with the key
it asks for as the default. The built-in file finder (`Ctrl+p`,
`:file-finder/open`) and project picker (`Alt+p`, `:project/open-picker`) are
such actions.

Some keys are part of a control rather than the keymap and cannot be changed
here: the web client's are listed under [Keyboard](web-client.md#keyboard),
the terminal editor has its own editing keys (`Ctrl+a`, `Ctrl+k`, `Ctrl+/`,
…).

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

### Actions without a default key

Bind these yourself. [Keyboard navigation](keyboard-navigation.md) shows how
the navigation ones fit together.

<!-- gen:keys-unbound -->

| Action | Does | Client |
| --- | --- | --- |
| `:diff/next-file` | Next file | web |
| `:diff/prev-file` | Previous file | web |
| `:files/find-edited` | Find a file edited in this chat | web |
| `:links/find` | Find a link or file mentioned in this chat | web |
| `:links/urls` | Find a URL mentioned in this chat | web |
| `:links/files` | Find a file mentioned in this chat | web |
| `:git/status` | Git status | web |
| `:git/log` | Git log | web |
| `:palette/open` | Command palette | web |
| `:hints/show` | Link hints: click a button or link by typing its label | web |
| `:hints/sidebar` | Link hints on the sidebar (opens the drawer) | web |
| `:projects/pick` | Pick a project | web |
| `:skills/search` | Search skills | web |
| `:themes/pick` | Change color theme | web |
| `:projects/open` | All projects | web |
| `:workspace/switch` | Switch workspace | web |
| `:workspace/new` | New workspace | web |
| `:workspace/rename` | Rename the current workspace | web |
| `:workspace/delete` | Remove the current workspace | web |
| `:workspace/next` | Next workspace tab | web |
| `:workspace/prev` | Previous workspace tab | web |
| `:workspace/other` | Back to the previously selected workspace | web |
| `:workspace/tab-1` | Workspace tab 1 (All) | web |
| `:workspace/tab-2` | Workspace tab 2 | web |
| `:workspace/tab-3` | Workspace tab 3 | web |
| `:workspace/tab-4` | Workspace tab 4 | web |
| `:workspace/tab-5` | Workspace tab 5 | web |
| `:workspace/tab-6` | Workspace tab 6 | web |
| `:workspace/tab-7` | Workspace tab 7 | web |
| `:workspace/tab-8` | Workspace tab 8 | web |
| `:workspace/tab-9` | Workspace tab 9 | web |
| `:workspace/tab-last` | Last workspace tab | web |
| `:nav/back` | Back to the previous page | web |
| `:nav/forward` | Forward to the next page | web |
| `:chat/hide` | Hide this chat from Recent | web |
| `:chat/delete` | Delete this chat | web |
| `:prompt/prev` | Previous message of yours | web |
| `:prompt/next` | Next message of yours | web |
| `:answer/prev` | Previous answer of the agent | web |
| `:answer/next` | Next answer of the agent | web |
| `:block/prev` | Previous agent block | web |
| `:block/next` | Next agent block | web |
| `:diff/next-hunk` | Next hunk | web |
| `:diff/prev-hunk` | Previous hunk | web |
| `:buffers/switch` | Switch buffer | web |
| `:compose/focus` | Focus the message box | web |
| `:timeline/bottom` | Scroll to the bottom | web |
| `:block/toggle` | Open / close the current block | web |
| `:scroll/top` | Scroll to the top | web |
| `:scroll/down` | Scroll down | web |
| `:scroll/up` | Scroll up | web |
| `:scroll/half-down` | Scroll half a page down | web |
| `:scroll/half-up` | Scroll half a page up | web |
| `:layout/split-right` | Split the window, new one to the right | web |
| `:layout/split-down` | Split the window, new one below | web |
| `:layout/close-pane` | Close the window | web |
| `:layout/only` | Keep only this window | web |
| `:layout/focus-next` | Focus the next window | web |
| `:layout/focus-left` | Focus the window to the left | web |
| `:layout/focus-right` | Focus the window to the right | web |
| `:layout/focus-up` | Focus the window above | web |
| `:layout/focus-down` | Focus the window below | web |
| `:layout/swap` | Swap the window with its neighbour | web |
| `:layout/dock-left` | Move the window into the left sidebar | web |
| `:layout/dock-right` | Move the window into the right sidebar | web |
| `:layout/undock` | Move the sidebar window back into the main area | web |
| `:layout/toggle-left` | Show / hide the left sidebar | web |
| `:layout/toggle-right` | Show / hide the right sidebar | web |
| `:layout/grow-width` | Widen the window | web |
| `:layout/shrink-width` | Narrow the window | web |
| `:layout/grow-height` | Make the window taller | web |
| `:layout/shrink-height` | Make the window shorter | web |
| `:layout/balance` | Equal sizes for the windows side by side | web |
| `:layout/reset` | Back to the single chat window | web |
| `:layout/undo` | Undo the last window change | web |
| `:layout/redo` | Redo the undone window change | web |

<!-- /gen:keys-unbound -->

`:chat/hide` and `:chat/delete` only run on a chat page.

### Vim-style keys

The default keys are modifier chords, arrows and named keys; single letters
and vim motions are left to you. A vim-style setup for the web client's
navigate mode is under [Keyboard navigation](keyboard-navigation.md). For the
sidebar and the views:

```clojure title="~/.config/xi/config.edn"
:keys {:web {:global          {"alt+j"       :session/next
                               "alt+k"       :session/prev
                               "alt+shift+j" :session/next-sibling
                               "alt+shift+k" :session/prev-sibling}
             :buffer/diff     {"q" :buffer/close
                               "[" :diff/prev-hunk
                               "]" :diff/next-hunk}
             :page/git-status {"[" :diff/prev-hunk
                               "]" :diff/next-hunk}
             :buffer/file     {"q" :buffer/close}
             :buffer/terminal {"q" :buffer/close
                               "i" :terminal/focus}}}
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
                             ["space" "s" "l"]  :links/find
                             ["space" "p" "p"]  :projects/pick
                             ["space" "b" "b"]  :buffers/switch
                             ["space" "g" "g"]  :git/status
                             ["space" "g" "l"]  :git/log
                             ["space" "g" "c"]  "/commit"
                             ["space" "c" "n"]  :chat/new
                             ["space" "c" "u"]  :session/jump-attention
                             ["space" "c" "x"]  :chat/delete
                             ["space" "c" "h"]  :chat/hide}}}
```

### Windows

The [window](web-client.md#windows) actions have no default keys. Doom
Emacs' `SPC w` map, as leader keys (a bare shifted character is written as
itself, `"<"`):

```clojure title="~/.config/xi/config.edn"
:keys {:web {:mode/navigate {["space" "w" "v"] :layout/split-right
                             ["space" "w" "s"] :layout/split-down
                             ["space" "w" "d"] :layout/close-pane
                             ["space" "w" "o"] :layout/only
                             ["space" "w" "w"] :layout/focus-next
                             ["space" "w" "h"] :layout/focus-left
                             ["space" "w" "j"] :layout/focus-down
                             ["space" "w" "k"] :layout/focus-up
                             ["space" "w" "l"] :layout/focus-right
                             ["space" "w" "H"] :layout/dock-left
                             ["space" "w" "L"] :layout/dock-right
                             ["space" "w" "u"] :layout/undo
                             ["space" "w" "ctrl+r"] :layout/redo
                             ["space" "w" "U"] :layout/undock
                             ["space" "w" "r"] :layout/swap
                             ["space" "w" "<"] :layout/shrink-width
                             ["space" "w" ">"] :layout/grow-width
                             ["space" "w" "-"] :layout/shrink-height
                             ["space" "w" "+"] :layout/grow-height
                             ["space" "w" "="] :layout/balance
                             ["space" "w" "["] :layout/toggle-left
                             ["space" "w" "]"] :layout/toggle-right
                             ["space" "w" "q"] :layout/reset}}}
```

A resize key moves the focused window by one step; for a continuous
resize, Tab onto the seam between two windows and hold an arrow key.

### Opening blocks

`:block/toggle` opens or closes the current block of a chat: the tool call
waiting on a permission request, else the running one, else the last tool,
thinking or step-group block. Opening a block inside a folded step group
opens the group too. No default key; bind it in `:page/chat` to have it in
the message box as well:

```clojure title="~/.config/xi/config.edn"
:keys {:web {:page/chat {"alt+shift+=" :block/toggle}}}   ; Alt++ on a US layout
```
