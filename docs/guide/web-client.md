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

- **Chat.** Send messages, attach images (paste, or the picker), stop a
  running turn, and answer permission requests with Allow or Deny.
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

| Key | Does |
| --- | --- |
| `i` | Focus the message box |
| `Esc` | Leave the message box |
| `G` | Scroll to the bottom |
| `Alt+j` / `Alt+k` | Next / previous chat |
| `Alt+n` | New chat |
| `Alt+u` | Jump to the newest finished chat with unread output |
| `Alt+a` / `Alt+d` | Allow / deny the pending permission request |
| `Alt+x` | Stop the running turn |
| `Ctrl/Cmd+k` | Command palette |
| `Ctrl/Cmd+p` | Find a file |

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
run shows as one summary line with a step count). Settings are per browser;
"Reset to defaults" drops them.

## Reference

How the client stays usable offline, in the repository:
[`docs/web-offline.md`](../web-offline.md).
