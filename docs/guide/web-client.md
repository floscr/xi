# The web client

Xi's browser client shows the same chats as your terminal, on any device that
can reach the server. Start a task at your desk and follow it on your phone.

## Opening it

Run Xi as a server:

```sh
xi server              # terminal client + web client
xi server --headless   # web client only, no terminal
```

Then open `http://localhost:7474`. On the same machine, that is all. The
chat list shows every session; the one you have open in the terminal is
marked as live.

## From another device

The server listens on every network interface, so a phone on the same Wi-Fi
or a Tailscale network reaches it at `http://<your-machine>:7474`.

The first time a new browser connects it shows a **four-digit pairing code**.
Approve it from a browser that is already paired (a banner appears there), or
from the terminal:

```sh
xi clients pending          # shows the code and the device name
xi clients approve 1234
```

The device is remembered; it never asks again. `xi clients` lists approved
devices and `xi clients revoke <name>` removes one.

> On iPhone, a home-screen app served over plain HTTP loses its pairing on
> most launches. Serve the client over HTTPS to fix that; see
> [HTTPS](https.md).

## What you can do

- **Chat.** Send messages, attach images and files (paste an image, use the
  picker, or drag files from your desktop onto the chat), stop a running
  turn, and answer permission requests with Allow or Deny. The caret next to
  Deny holds **Deny with reason**; the one next to Allow lists the other
  grants the request offers (Allow all, Always, Allow repo writes, Recommend
  a rule) with what each one does. **Explain** on a
  permission request starts a [sub-agent](builtin-tools.md#sub-agents) that
  reads the call, the conversation and the session transcript and says what
  the call does, why the agent wants it and the risk. The answer appears
  under the tool block and stays there after you decide.
- **Read what the agent did.** Tool calls and thinking are collapsed blocks.
  Open one in place, or switch the appearance settings (gear in the sidebar
  footer) to keep them open.
- **Review changes.** An edit shows a diff where it happened. Right-click (or
  long-press) a code block to copy it, open the file, or open the diff as a
  buffer (below). In a diff buffer, right-click a file's header to open that
  file or copy its path.
- **Read Markdown changes as Markdown.** A diff of a Markdown file (an edit,
  a permission ask, the diff buffer, Git status) is shown rendered: changed
  paragraphs, list items, and code blocks get a red (removed) or green
  (added) bar, the words that changed inside them are highlighted, and
  unchanged context is dimmed. The **Rendered / Code** switch at the top right
  of the diff (in the file header, in the diff buffer) flips that diff to the
  line diff and back. A hunk is rendered on its own, so one that starts in the
  middle of a table or code block shows that part as plain text.
- **Switch projects.** The projects page lists your [projects](projects.md);
  start a new chat in any of them.
- **Run commands.** Everything from [Slash commands](commands.md) works here
  too. The command palette (Ctrl/Cmd+K) lists them.
- **Pin a chat.** The sidebar's Recent group shows the chats active in the
  last couple of days. "Pin session" (right-click a card, long-press, or its
  ⋮ menu) keeps a chat there for good: it never ages into Earlier, and "Hide
  all from Recent" / Prune skip it. "Unpin session" in the same menu undoes
  it. A chat is never pinned and hidden at once: pinning a hidden chat shows
  it again, hiding a pinned chat unpins it.

## Buffers

A chat can hold views next to its conversation: the diffs you opened
(`/diff`, the Git status entry, a commit from `/commits`), the files you
opened from a code block or the file finder, the system prompt (`/prompt`).
These are its **buffers**. Each file and each diff source is its own buffer,
so opening a second file keeps the first; opening the same one again
refreshes it in place.

- **Switching.** Once a chat has a buffer, the pill in the top bar names the
  view you are looking at. Click it for the list: the chat, every buffer in
  the order you opened them (the one in front is tinted), and the review
  canvas when there is one. `Alt+b` opens the same list in the command
  palette: type to filter, Enter to switch. `q` or `Esc` in a diff or file
  view goes back to the chat; the buffer stays open.
- **In the sidebar.** A session with buffers says so under its name ("3
  buffers"). Click the count to unfold the rows under the card, again to fold
  them; the browser remembers which you left open. The row of the buffer you
  are looking at is tinted. A row opens that chat on that buffer, so you can
  come back to the diff you were reading from anywhere.
- **Sub-agents.** A chat's running or finished
  [sub-agents](builtin-tools.md#sub-agents) list under its card with the
  buffers ("2 buffers · 1 sub-agent"), a spinner on the ones still running.
  A row opens the chat and unfolds that sub-agent in the Sub-agents panel.
  The trailing button stops a running one or dismisses a finished one.
  Sub-agents live with the chat's room: once the room closes they are gone
  from the list.
- **In the command palette** (Ctrl/Cmd+K), the Buffers group lists the
  current chat's buffers first, then every other session's.
- **Who is where.** On a server with several users, a buffer row shows the
  avatars of the people looking at it right now, in the pill's list and in
  the sidebar; the Chat row shows who is on the conversation.
- **Closing.** The × on a row (in the pill's list or in the sidebar) closes
  one; "Close all buffers" clears the chat's. Closing is for everyone in the
  chat: the buffer list is shared, which buffer each person is looking at is
  their own, so opening a diff on your phone never flips your laptop.
- **How long they live.** Buffers survive leaving the chat and coming back,
  on any device, as long as the server runs. They are not saved to disk: a
  server restart starts with none.

## Install it on your phone

Add the page to your home screen (Share → Add to Home Screen on iOS, Install
on Android). It opens full-screen, without the browser chrome.

Recent chats are cached on the device, so the list and the last open chat
paint at once, before the connection is back. Messages you send while offline
are kept and sent when the connection returns.

## Keyboard

Press `?` (while not typing) for the list of shortcuts that apply where you
are. The common ones:

| Key | Does |
| --- | --- |
| `i` | Focus the message box |
| `Esc` | Leave the message box |
| `Tab` (in the message box) | Expand the snippet word before the cursor: `c` becomes `continue`, `rec` becomes `in a recent change`. Anywhere else Tab moves focus as usual. |
| `↑`/`↓`, `Alt+k`/`Alt+j` or `Ctrl+p`/`Ctrl+n` (in the `/` command list) | Move through the matching commands; `Enter` or `Tab` runs the selected one |
| `G` | Scroll to the bottom |
| `Alt+j` / `Alt+k` | Next / previous chat |
| `Alt+n` | New chat |
| `Alt+u` | Jump to the chat that needs you most: one waiting on a permission request, then the newest finished chat with unread output, then the newest running one. Press again to move on to the next |
| `Alt+Shift+P` | Prune: run every cleanup the sidebar's "Prune all" would (mark all as read, hide all from Recent — pinned chats are skipped — close idle rooms) |
| `Alt+a` / `Alt+d` | Allow / deny the pending permission request |
| `Alt+x` | Stop the running turn |
| `Ctrl/Cmd+k` | Command palette |
| `Tab` or `Enter` (in the palette, on a project) | Open that project's actions: new chat, git status, search its sessions, open its sessions |
| `Tab` (in a project picker, on a project) | Find a file in that project instead: the picker's own action applies to the file, so from the compose box's Projects button it inserts the file's path |
| `Alt` (held, in the palette) | Show a key badge on each of the first rows; `Alt` + that key picks the row. Badges follow the filtered list, so type first, then hold Alt |
| `Ctrl/Cmd+p` | Find a file |
| `Alt+b` | Switch buffer |

The full list, the layers a key applies in, and how to change any of them in
`config.edn`: [Keyboard shortcuts](keyboard.md).

## Appearance

The gear in the sidebar footer (or "Appearance" in the chat menu and the
command palette) opens the appearance dialog: theme, whether tool and
thinking blocks start open or collapsed, and **super collapsed** (a run of
collapsed tool and thinking rows shows as one summary line with a step
count instead of its header rows). "Reset to defaults" drops them.

### Color themes

"Color theme" in the same dialog holds your own themes next to the default
one. A theme changes the hue and chroma of the gray and accent scales and
the status colors (success, warning, danger: status dots, usage meters,
errors), the page background of light and dark mode (each its own
color) with the sidebar a chosen step brighter or darker than it, the spacing base, the type
scale and the corner radius; light and dark mode stay separate and both use
it. "New" opens the editor with the defaults and a set
of presets to start from; the sliders change the page as you move them, and
"Save" (or the dialog's "Done") keeps the result under the name you typed;
closing the dialog any other way drops the changes. Pick a saved theme to use it
and click it again to edit or delete it; "Change color theme…" in the
command palette switches between them. Themes are per
[user](#what-follows-you), up to twenty.

### What follows you

These choices belong to your [user](server.md#users), not to the browser:
the theme and your color themes, the appearance settings, which sidebar
groups are collapsed, the
model new chats start with, and your recently used commands and skills. So do
the chats you have read (the unread dots), the chats you hid from Recent and
the ones you pinned to it: the same chat can be unread for you and read for a
colleague, and hidden or pinned for one of you only. What an
[extension](extensions.md) keeps about you, such as a list of favorite chats,
is yours too. Change them on your phone and your laptop follows; a colleague
on the same server has their own. The server keeps them per user in
`~/.config/xi/state/users/`. The
browser also keeps a copy so the page paints with the right theme before it
connects, and works offline.

The first time a browser connects after this was introduced, whatever it
already had is saved to the server for its user, so nothing resets. On a
browser shared by several users, one user's settings are never handed to
another.

## Reference

How the client stays usable offline, in the repository:
[`docs/web-offline.md`](../web-offline.md).
