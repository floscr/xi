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
  turn, and answer permission requests with Allow or Deny.
- **Read what the agent did.** Tool calls and thinking are collapsed blocks.
  Open one in place, or switch the appearance settings (gear in the sidebar
  footer) to keep them open.
- **Review changes.** An edit shows a diff where it happened. Right-click (or
  long-press) a code block to copy it, open the file, or open the diff in the
  Diff tab.
- **Switch projects.** The projects page lists your [projects](projects.md);
  start a new chat in any of them.
- **Run commands.** Everything from [Slash commands](commands.md) works here
  too. The command palette (Ctrl/Cmd+K) lists them.

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
| `G` | Scroll to the bottom |
| `Alt+j` / `Alt+k` | Next / previous chat |
| `Alt+n` | New chat |
| `Alt+u` | Jump to the chat that needs you most: one waiting on a permission request, then the newest finished chat with unread output, then the newest running one. Press again to move on to the next |
| `Alt+Shift+P` | Prune: run every cleanup the sidebar's "Prune all" would (mark all as read, hide all from Recent, close idle rooms) |
| `Alt+a` / `Alt+d` | Allow / deny the pending permission request |
| `Alt+x` | Stop the running turn |
| `Ctrl/Cmd+k` | Command palette |
| `Tab` or `Enter` (in the palette, on a project) | Open that project's actions: new chat, git status, search its sessions, open its sessions |
| `Alt` (held, in the palette) | Show a key badge on each of the first rows; `Alt` + that key picks the row. Badges follow the filtered list, so type first, then hold Alt |
| `Ctrl/Cmd+p` | Find a file |

The full list, the layers a key applies in, and how to change any of them in
`config.edn`: [Keyboard shortcuts](keyboard.md).

## When something is off

**"Connecting…" and nothing happens.** The server is not reachable at the
address in the URL. Check `xi server` is running, and that the port (7474) is
open to your network.

**The pairing code never shows up on the terminal.** `xi clients pending`
reads it from the server's state; run it on the machine the server runs on.

**The page is stale after an update.** Reload once; the client is served by
the same process as the server, so a restarted server serves the new client.

## Appearance

The gear in the sidebar footer (or "Appearance" in the chat menu and the
command palette) opens the appearance dialog: theme, whether tool and
thinking blocks start open or collapsed, **viewer mode** (runs of tool calls
fold into one box of header rows) and **super collapsed** (a fully collapsed
run shows as one summary line with a step count). "Reset to defaults" drops
them.

### What follows you

These choices belong to your [user](server.md#users), not to the browser:
the theme, the appearance settings, which sidebar groups are collapsed, the
model new chats start with, and your recently used commands and skills. So do
the chats you have read (the unread dots) and the chats you hid from Recent:
the same chat can be unread for you and read for a colleague, and hidden for
one of you only. What an [extension](extensions.md) keeps about you, such as
a list of favorite chats, is yours too. Change
them on your phone and your laptop follows; a colleague on the same server has
their own. The server keeps them per user in `~/.config/xi/state/users/`. The
browser also keeps a copy so the page paints with the right theme before it
connects, and works offline.

The first time a browser connects after this was introduced, whatever it
already had is saved to the server for its user, so nothing resets. On a
browser shared by several users, one user's settings are never handed to
another.

## Reference

How the client stays usable offline, in the repository:
[`docs/web-offline.md`](../web-offline.md).
