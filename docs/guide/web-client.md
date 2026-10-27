# The web client

Xi's browser client shows the same chats as your terminal, on any device that
can reach the server. Start a task at your desk and follow it on your phone.

## Opening it

Run Xi as a server:

```sh
xi start               # server in the background, opens the browser signed in
xi server              # terminal client + web client
xi server --headless   # web client only, no terminal
```

With `xi server`, open `http://localhost:1414`. On the same machine, that is all. The
chat list shows every session; the one you have open in the terminal is
marked as live.

## From another device

The server listens on every network interface, so a phone on the same Wi-Fi
or a Tailscale network reaches it at `http://<your-machine>:1414`.

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

## Home

The home page (`/`) starts chats and shows cards.

- The box at the top starts a chat in the project picked under it, the most
  recently used one unless you pick another; "No project" uses the server's
  default directory. Enter sends, Shift+Enter starts a new line. A message
  that starts with `/` opens the new chat with it typed in, so the command
  menu completes it.
- **Active**: chats waiting on a permission answer, failed, with unread
  replies, or running, in that order.
- **Recent chats**, **Projects** (+ starts a chat in one) and **Usage** (the
  [usage](#usage) meters).
- **Extensions**, only when a [user extension](extensions.md) failed to load
  or is not listed in `config.edn`: the file and the error.

**Customize dashboard** in the ⋮ menu switches cards on and off.
[Extensions](extensions-reference.md#browser-halves) can add cards. **All
projects** in the sidebar opens the project list (`/projects`). The tab bar
across the top narrows the cards and lists to one
[workspace](workspaces.md).

## What you can do

- **Chat.** Send messages, attach images and files (paste an image, use the
  picker, or drag files from your desktop onto the chat), stop a running
  turn, and answer permission requests with Allow or Deny. The caret next to
  Deny holds **Deny with reason**; the one next to Allow lists the other
  grants the request offers (Allow all, Always, Allow repo writes, Allow for
  this chat, Recommend a rule) with what each one does. **Explain** on a
  permission request starts a [sub-agent](builtin-tools.md#sub-agents) that
  reads the call and the conversation and says what
  the call does, why the agent wants it and the risk. The answer appears
  under the tool block and stays there after you decide.
- **Edit an attached image.** Click its thumb in the composer: the toolbar
  of the viewer crops (`C`) and draws highlight rectangles (`R`) in the
  color you pick, with undo (`Ctrl+Z`), redo and remove all edits. The
  message sends the edited image.
- **Sub-agents in the chat.** When the agent (or `/subagent`) starts a
  [sub-agent](builtin-tools.md#sub-agents), one row stands where that
  happened, in place of the spawn call: its label, status, running time and
  one line of what it does (the latest tool, its answer's first line, or
  "needs you"). Sub-agents started together (a commit plan per repo) share
  one row with their count and how many finished. Click a row to open the
  sub-agent in the right sidebar ([Windows](#windows)), laid out like a
  chat: its task, its tool and thinking blocks (open or collapsed like the
  chat's own, [Appearance](#appearance)), its answer, and a composer with
  image attachments. The window bar holds its actions (Open as chat, Keep
  as session, Dismiss). A shared row opens their list there; pick one, and
  its **‹** link goes back to the list. While a sub-agent runs, the
  composer shows a spinner and Stop, and a message goes into its running
  turn; once it finished, a message continues its own conversation. The
  sub-agent stays in this chat; it does not become a chat of its own.
- **Agents button.** The action row's **Agents** button lists every
  sub-agent of the chat, under the message that started it; the amber count
  is how many wait on you. Pick one to open it in the right sidebar.
- **A sub-agent's permission requests** show in its sidebar view, not in
  the chat: its row says "needs you" and the Agents button counts it.
  **Allow all** on a shared row's list answers every request of that group.
  The sub-agent waits until you answer, and stopping it denies them.
- **Saved sub-agents.** A finished sub-agent is saved as a session of its
  own, hidden from your chats and deleted with the chat. The chat keeps the
  link, so its row, its answer and **Open as chat** work after a restart.
  **Keep as session** lists it with your other chats; so does the same
  button on top of the sub-agent's chat.
- **Ask about any block.** Hover a message, tool call or thinking block and
  click the ⚡ button at its right edge. On a phone, tap the block (or
  long-press a tool header); for a code block or your own message, it is in
  the block's menu. A field opens under the block with suggested questions.
  The question goes to a [sub-agent](builtin-tools.md#sub-agents) that gets
  the block and the conversation; its answer streams in under the block,
  where you can reply, collapse the thread, or close it. Blocks in a
  sub-agent's sidebar view work the same, and their threads show there.
  The agent sees your questions in `list_subagents`.
  **Select text** in a block with the mouse and the ⚡ button moves to the
  end of the selection: the question is then about that text, quoted in
  the field and in the thread.
- **Queue messages.** A message sent while the agent works waits for the
  turn to end; the number in the action row opens the queue. Its steer
  button sends a message into the running turn (see Alt+Enter below), ▶
  runs it in a [sub-agent](builtin-tools.md#sub-agents) right away instead,
  × drops it. `Alt+Enter` (or Alt+click on send) sends the message into
  the running turn instead: the agent reads it after its current tool call
  and goes on with it in mind. It appears in the chat at that point; a
  message the turn could no longer take stays in the queue. Holding Alt
  turns the send button into the steer icon while that works: not while a
  permission request waits, when the message queues as usual. On a touch
  screen a tap on send steers and holding it queues: a ring fills, and
  the message queues when it closes. Slide away to cancel.
- **Read what the agent did.** Tool calls and thinking are collapsed blocks.
  Open one in place (it stays open when you come back from another chat),
  or switch the appearance settings (gear in the sidebar footer) to keep
  them open.
- **Review changes.** An edit shows a diff where it happened. Right-click (or
  long-press) a code block to copy it, open the file, or open the diff as a
  buffer (below); inline code naming a file that exists under the cwd
  (`src/a.cljs:12`) offers to open it too. Right-click (or long-press) the
  header row of a tool block that names a file, or the bar above a rendered
  Markdown diff, to open the file, copy its path, open the diff, or ask about
  the block. In a diff buffer, right-click a file's header to open that file
  or copy its path. The welcome card's cwd and instruction files have the
  same menu: find a file in the cwd, open an instruction file.
- **Tables.** The bar above a rendered table shows its size, a **Table /
  List** switch (list mode shows one entry per row, with the column names as
  labels), and **⋯** to copy the table as Markdown or as tab-separated text
  that pastes into a spreadsheet as cells.
- **Diff buffer.** The bar above a diff picks its source, its renderer
  (**git**, or **difftastic** for structural changes: what changed within a
  line is highlighted, the code's own colors are dimmed) and its layout:
  **Stacked** (old above new) or **Split** (side by side). The layout is
  remembered; until you pick one, git diffs open stacked and difftastic ones
  split. Difftastic needs `difft` on the server's `PATH`.
- **Work on diff lines.** Tap a line in a diff buffer, then a second one to
  select the range. **Ask** a question about it, **Explain** it, or
  **Modify** it (describe the change). Each runs a
  [sub-agent](builtin-tools.md#sub-agents) whose answer shows under the
  lines, where you can reply or close it. A Modify edits the files, asking
  for permission like any sub-agent; reopen the diff to see its change. An
  Ask changes files only when you ask it to, and asks you before every
  edit, even in yolo mode. Permission asks show in the thread. Once a
  thread's sub-agents have edited files, **‹ ›** at the top of the thread
  step through their changes, one diff at a time.
- **From a reply into the diff.** While the chat has a diff buffer, inline
  code in the agent's replies that names something in it is a link: click
  `reference-updates` to see the line defining it (else the first changed
  line mentioning it), `src/app.cljs:12` to see that line. The diff opens on
  that line, selected, so Ask / Explain / Modify are right there. A chip
  that points nowhere does nothing.
- **Read Markdown changes as Markdown.** A diff of a Markdown file (an edit,
  a permission ask, the diff buffer, Git status) is shown rendered: changed
  paragraphs, list items, and code blocks get a red (removed) or green
  (added) bar, the words that changed inside them are highlighted, and
  unchanged context is dimmed. The **Rendered / Code** switch at the top right
  of the diff (in the file header, in the diff buffer) flips that diff to the
  line diff and back. A hunk is rendered on its own, so one that starts in the
  middle of a table or code block shows that part as plain text.
- **Switch projects.** The projects page (**All projects** in the sidebar)
  lists your [projects](projects.md); start a new chat in any of them.
- **Run commands.** Everything from [Slash commands](commands.md) works here
  too. Typing `/` in the composer suggests them, plus the skills you have
  run, the ones you use most in this browser first. A skill picked there
  opens its input form; `/<skill> text` runs it at once, the text filling
  its first input. The command palette (Ctrl/Cmd+K) lists them.
- **Find a link or file of the chat.** "Find link or file in this chat…" in
  the command palette lists the URLs and files the chat mentions, newest
  first: URLs from the messages and tool arguments, files the tools read or
  edited and file paths in the messages. A URL opens in a new tab, a file in
  a buffer. `:links/find`, `:links/urls` and `:links/files` open the list,
  only the URLs or only the files ([keys](keyboard.md#actions-without-a-default-key)).
- **Pin a chat.** The sidebar's Recent group shows the chats active in the
  last couple of days. Opening an older chat leaves it in Earlier until it
  gets a new turn. "Pin session" (right-click a card, long-press, or its
  ⋮ menu) keeps a chat there for good: it never ages into Earlier, and "Hide
  all from Recent" / Prune skip it. "Unpin session" in the same menu undoes
  it. A chat is never pinned and hidden at once: pinning a hidden chat shows
  it again, hiding a pinned chat unpins it.
- **Rename a chat.** "Rename…" in the same menu. While you type, Claude
  Haiku suggests three names from the conversation; click one, or press Tab
  to cycle them into the field. **More** asks for three new ones.
- **Share a chat.** "Copy link" in the same menu, in the chat's ⋮ menu and
  in the command palette copies the chat's address
  (`http://<server>/chat/<id>`); it opens the chat on any device paired
  with the server. On a device with a share sheet (a phone, Safari), "Share
  link…" next to it opens the sheet with the address instead.
- **Prune.** "Prune all" in the sidebar (`:sessions/prune`) runs every
  cleanup at once: mark all as read, hide all from Recent (pinned chats are
  skipped), close idle rooms.

## Buffers

A chat can hold views next to its conversation: the diffs you opened
(`/diff`, the Git status entry, a commit from `/commits` or `/log`), the files you
opened from a code block, the file finder or a link to a file in a message or
viewed markdown file or diff (relative to the chat's directory or that file; a
`:line` suffix is ignored, a `#heading` scrolls a markdown file to that
heading), the system prompt (`/prompt`),
terminals (`/term`) and the review canvas (`/canvas-review`).
These are its **buffers**. Each file and each diff source is its own buffer,
so opening a second file keeps the first; opening the same one again
refreshes it in place.

- **Switching.** Once a chat has a buffer, the pill in the top bar names the
  view you are looking at. Click it for the list: the chat, every buffer in
  the order you opened them (the one in front is tinted). `:buffers/switch` (unbound by default) opens
  the same list in the command palette: type to filter, Enter to switch. `Esc` in a diff or file
  view goes back to the chat; the buffer stays open.
- **In the URL.** The address names the buffer you are looking at
  (`/chat/<id>/diff%3Agit`), so a reload or a shared link opens it, and
  each switch is a browser history entry: Back returns to the previous view.
  Back onto a buffer closed since lands on the chat.
- **In the sidebar.** A session with buffers says so under its name ("3
  buffers"). Click the count to unfold the rows under the card, again to fold
  them; the browser remembers which you left open. The row of the buffer you
  are looking at is tinted. A row opens that chat on that buffer, so you can
  come back to the diff you were reading from anywhere. The chat's own card
  opens it on the view you left it on (the chat after `Esc`, else the
  buffer); on the chat you are in, it goes back to the chat. `:session/next` /
  `:session/prev` pass over buffer rows; on the card of the chat you are in,
  `:session/next-sibling` steps into them and `:session/prev-sibling` from
  the first back out ([keys](keyboard.md#default-keys)).
- **Sub-agents.** A chat's running or finished
  [sub-agents](builtin-tools.md#sub-agents) list under its card with the
  buffers ("2 buffers · 1 sub-agent"), a spinner on the ones still running.
  A row shows the sub-agent in place of the chat, like a buffer: its task
  and output, a field to steer it while it runs or reply once it has
  finished, and buttons to stop it,
  open it as a chat of its own or dismiss it. The topbar pill lists the chat's sub-agents too, and
  the chat shows them where they were started (above).
  The trailing button stops a running one or dismisses a finished one.
  The list shows the sub-agents of open chats; the chat saves the finished
  ones and shows them again when you open it.
- **In the command palette** (Ctrl/Cmd+K), the Buffers group lists the
  current chat's buffers first, then every other session's.
- **Who is where.** On a server with several users, a buffer row shows the
  avatars of the people looking at it right now, in the pill's list and in
  the sidebar; the Chat row shows who is on the conversation.
- **Closing.** The × on a row (in the pill's list or in the sidebar) closes
  one; "Close all buffers" clears the chat's, and "Close buffers of all
  sessions" in the palette clears every chat's (sub-agents stay). Closing is
  for everyone in the chat: the buffer list is shared, which buffer each person is looking at is
  their own, so opening a diff on your phone never flips your laptop.
- **How long they live.** Buffers survive leaving the chat and coming back,
  on any device, as long as the server runs. They are not saved to disk: a
  server restart starts with none.

## Windows

A chat shows one view at a time until you split it. **Split window right**
or **Split window down** (the ⋮ menu, the palette's Windows group, or a
window's own bar) divides the view in two; the new window asks what to
show: the chat, a buffer, a sub-agent, a file or the git status. Every
window has a bar with its view pill (the top bar's list, for that window;
the top bar's own pill goes away while the chat is split), split buttons,
two buttons that move it into the left or right **sidebar**, and ×. Up to
eight windows; the chat appears in one of them at most.

- **Focus.** The window you last clicked is the focused one (an accent
  outline while more than one window shows). The URL, the sidebar's tinted row and the keys all follow it: a buffer opened from anywhere (the sidebar, the
  palette, a file link) shows in the focused window, or focuses the window
  already showing it. `Esc` in a diff or file window puts the chat there.
- **Resizing.** Drag the seam between two windows. Each seam is a keyboard
  control too: Tab onto it, then the arrow keys resize (Shift for bigger
  steps), Home / End go to the ends, and a double-click or Enter resets. The
  `:layout/grow-*` / `:layout/shrink-*` actions resize the focused window
  by a step without the mouse, `:layout/balance` makes the windows of a
  split equal ([keys](keyboard.md#windows)).
- **Sidebars.** A window moved into a sidebar keeps its width across the
  chat's splits and can be hidden and shown (`:layout/toggle-left` /
  `:layout/toggle-right`, or drag the seam shut). One window per side;
  moving another one there swaps them. Its bar's first button moves it
  back into the main area.
- **On a phone** (and any window under 1024px wide), the main area shows
  the focused window alone; its pill switches what it shows. A sidebar is a
  drawer over the chat: open it with its toggle action, from a sub-agent's
  row, or by swiping in from the right edge; tap the dimmed
  chat, swipe right or toggle again to close it.
- **Sub-agents.** A sub-agent's row in the chat, or its entry under the
  Agents button, opens it in the right sidebar.
- **Undo.** Every change of the arrangement or the sizes can be taken back
  (`:layout/undo`, the palette's "Undo window change") and redone
  (`:layout/redo`), like Emacs' winner mode: the last fifty changes of the
  chat, for as long as the page is open. Moving focus is not a change.
- **Per chat, per user.** The arrangement and sizes belong to the chat: a
  split chat comes back split, on every device of yours (the last fifty
  chats you arranged). Another user of the same chat has their own.
  **Back to one window** (⋮ menu, palette, `:layout/reset`) clears it;
  undo brings it back.

## Terminals

A terminal is a buffer running a program on the server, in the chat's
directory. `/term` opens your shell (`$SHELL`), `/term <command …>` runs that
command instead (quotes keep spaces: `/term nvim "my notes.md"`). **New
terminal** in the ⋮ menu and the command palette opens a shell.

- **Typing.** While the terminal has focus, keys go to the program, plain
  Ctrl keys too (Firefox's Ctrl+D does not bookmark). `` Ctrl+` `` or `Alt+\`
  leaves the terminal; then `Esc` goes back to the chat, `Enter` back into the
  terminal ([keys](keyboard.md#default-keys)). A terminal you reach by
  walking the sidebar (`:session/next` and friends) does not take focus, so
  the next press still switches sessions.
- **Lifetime.** The program keeps running while you look at another buffer,
  leave the chat or reload the page, and every device in the chat sees the
  same screen. It takes the size of the window you use last: the one you
  resize, type in or come back to. When it exits with code 0, its buffer closes; any other exit
  keeps the output with **Restart** and **Close**. Closing the buffer ends the
  program. A server restart ends every terminal.
- **Colours.** The terminal takes its colours and its font size (the
  theme's code size, see [Color themes](#color-themes)) from the current
  theme and follows a theme switch at once. The 16 ANSI colours keep their usual
  meaning and carry the code-block colours: 0 surface, 1 danger, 2 success,
  3 warning, 4 keyword, 5 symbol, 6 function, 7 secondary text, 8 comment,
  9 operator, 10 string, 11 warning, 12 accent, 13 number, 14 builtin, 15
  text. Programs see `TERM_PROGRAM=xi`, so an editor can pick a colorscheme
  that uses only those 16 colours and looks like the chat in every theme.
- **Xi actions from a program.** A program can run some Xi actions by
  writing `ESC ] 7474 ; <action> BEL`, for example a vim mapping that opens
  the command palette:
  `nnoremap <C-k> <Cmd>call nvim_ui_send("\e]7474;palette/open\x07")<CR>`.
  Only actions that open or switch a view run: `palette/open`,
  `buffers/switch`, `files/find`, `files/find-edited`, `links/find`,
  `links/urls`, `links/files`, `git/status`,
  `git/log`, `projects/pick`, `skills/search`, `themes/pick`, `keys/show`,
  `sidebar/toggle`, `buffer/close`, `terminal/leave`.
- **Tooltips from a program.** `ESC ] 7475 ; <base64 JSON> BEL` shows a
  tooltip below a screen cell, for example the editor's diagnostics under
  its cursor. The JSON is
  `{"row": 4, "col": 12, "items": [{"severity": "error", "text": "…", "source": "clj-kondo"}]}`:
  `row` and `col` count from 0, `severity` is `error`, `warn`, `info` or
  `hint`, `source` is optional. An empty payload hides the tooltip; a
  resize hides it too, so send it again on one. **Hide terminal tooltips**
  in the command palette turns them off for your user (**Show terminal
  tooltips** turns them back on).
- **Clipboard.** A program copies to the clipboard of the device you use
  with OSC 52 (`ESC ] 52 ; c ; <base64> BEL`). Reading the clipboard this way
  is not supported; paste with the browser. In Neovim:

  ```lua
  -- p pastes the last yank: Xi answers no clipboard query
  local osc52, last = require("vim.ui.clipboard.osc52"), { {}, "v" }
  local function copy(reg)
    local send = osc52.copy(reg)
    return function(lines, regtype) last = { lines, regtype }; send(lines, regtype) end
  end
  local function paste() return last end
  vim.g.clipboard = {
    name = "OSC 52",
    copy = { ["+"] = copy("+"), ["*"] = copy("*") },
    paste = { ["+"] = paste, ["*"] = paste },
  }
  ```

- **Prompts from a program.** A program can send its chat a prompt, or
  start a sub-agent there, through the socket in `XI_TERM_SOCK`, with the
  token in `XI_TERM_TOKEN`. The prompt counts as sent by whoever opened the
  terminal. `label` is what the chat shows instead of the full text:

  ```sh
  curl --unix-socket "$XI_TERM_SOCK" -H "Authorization: Bearer $XI_TERM_TOKEN" \
    -H 'Content-Type: application/json' \
    -d '{"text": "Explain main.clj", "to": "chat", "label": "Explain"}' http://xi/send
  ```

  `"to": "subagent"` starts a sub-agent instead; with `"stream": true` the
  answer streams its reply as JSON lines (`text`, `tool`, `error`, then
  `done`). Otherwise the answer is `{"ok": true}`,
  or `{"error": …}` with status 400 (body isn't JSON), 401 (unknown token)
  or 409 (no text, or the chat isn't open).
- **Toolbar.** On a touch screen, floating buttons at the bottom right send
  Esc, Tab, Ctrl+C and the arrow keys. Extensions add buttons there and
  entries to the code-block menu, for example "Open in vim"
  ([reference](extensions-reference.md#browser-halves)).
- **Who may.** Opening a terminal is a rules request with `:tool :terminal`.
  No built-in rule matches it, so it runs; a rule can deny it, for example
  for some users ([rules reference](rules-reference.md)).

## Usage

The ring in the sidebar footer shows how much of the Claude login's 5-hour
window is used; click it for the session and weekly windows. **All usage**
there (or "Usage" in the command palette and the ⋮ menu) opens the
`/usage` page: one card per account the server can read.

- **Claude**: the Claude Code login. Every window the plan has (5-hour, 7-day,
  per-model), when each resets, the pace it is on ("≈6%/h · on pace for 32%
at reset"), and when the sign-in renews.
- **OpenAI**: the Codex CLI login (`codex login`), its 5-hour and weekly
  windows and credits.
- **Ollama Cloud**: with `OLLAMA_API_KEY` set, the included and purchased
  credits and the last 30 days' spend.
- **OpenCode Go**: the sign-in (`/login opencode`) or an OpenCode Go key
  (`OPENCODE_API_KEY` or the OpenCode CLI's login), its rolling, weekly and
  monthly windows.
- **Stored accounts**: each Claude and OpenCode Go account kept with
  [`/account`](models.md#accounts) that isn't live, marked "Stored". A
  stored Claude account's card is fresh while its token holds; after that
  it shows its last reading for an hour.

A 7-day window draws the week so far as a line, dotted ahead at the current
pace; a 5-hour window draws the peak of each earlier window. The server
samples every account every five minutes and keeps two weeks of samples in
`~/.config/xi/state/usage-history.edn`, so the lines survive a restart. The
page follows each poll; the refresh button asks for one now.

An [extension](extensions-reference.md) can add accounts of its own to the
page, for example the other logins of an account pool.

## Accounts

The `/accounts` page ("Accounts" in the command palette and the ⋮ menu)
lists every provider's stored [accounts](models.md#accounts): Switch makes
one live and renews an expired token, and an account's ⋮ menu renames it
(its alias) or forgets it. "Add another account" starts a sign-in with an
optional alias, in steps: open the sign-in page, then enter the code shown
here on that page, or, for Claude, paste the code that page shows. The
palette offers "Switch … to …" for each account once the page has loaded
them.

## Install it on your phone

Add the page to your home screen (Share → Add to Home Screen on iOS, Install
on Android). It opens full-screen, without the browser chrome.

Recent chats are cached on the device, so the list and the last open chat
paint at once, before the connection is back. Messages you send while offline
are kept and sent when the connection returns.

## Keyboard

Press `?` (while not typing) for the list of shortcuts that apply where you
are. Its **Commands** and **Skills** tabs list every slash command and skill
with its description and where it comes from (built-in, an extension's
file, the skill's directory); click one to run it. The keymap's keys, the
layers they apply in and how to change them in `config.edn`: [Keyboard
shortcuts](keyboard.md). Using the client without a mouse, with link hints:
[Keyboard navigation](keyboard-navigation.md).

These keys belong to a control and are not in the keymap:

| Key | Does |
| --- | --- |
| `Ctrl/Cmd+k` | Command palette. Binding `:palette/open` to a modifier chord in `:global` (`"ctrl+x"`) makes that the palette's key instead, in text fields too, and frees `Ctrl+k` |
| `Tab` (in the message box) | Expand the snippet word before the cursor: `c` becomes `continue`, `rec` becomes `in a recent change`. Anywhere else Tab moves focus as usual. |
| `Alt+Enter` (in the message box, while the agent works) | Send the message into the running turn instead of the queue; holding Alt shows the steer icon on the send button. While the agent is idle, or a permission request waits, it sends like `Enter` |
| `Ctrl+Enter` (on one of your messages) | Edit it in place: its attachments stay above the bubble with an × each, and `Ctrl+V` in the bubble adds a pasted image. Save goes back to before the message and sends the new text with them |
| `↑`/`↓`, `Alt+k`/`Alt+j` or `Ctrl+p`/`Ctrl+n` (in the `/` command list) | Move through the matching commands; `Enter` or `Tab` runs the selected one |
| `Tab` or `Enter` (in the palette, on a project) | Open that project's actions: new chat, git status, search its sessions, open its sessions |
| `Alt+Enter` (in a project's actions, on New chat) | Type a prompt instead (`Ctrl+V` attaches images, shown below it); `Enter` starts the chat in the background and you stay where you are. The chat shows up under Recent |
| `Tab` (in a project picker, on a project) | Find a file in that project instead: the picker's own action applies to the file, so from the compose box's Projects button it inserts the file's path |
| `Alt` (held, in the palette) | Show a key badge on each of the first rows; `Alt` + that key picks the row. Badges follow the filtered list, so type first, then hold Alt |

## Appearance

The gear in the sidebar footer (or "Appearance" in the chat menu and the
command palette) opens the appearance dialog. Its side menu (a strip at
the top on a narrow screen) has three panes; a sub-pane's top bar drills
back to its parent. **Theme** holds light, dark or
system mode and the [color themes](#color-themes). **Chat** holds whether
tool and thinking blocks start open or collapsed, its **Tool blocks**
sub-pane the tools that start the other way (a searchable list of every
tool with a switch each, the switched-on ones first, then built-in tools,
Xi's extensions, each of your [extensions](extensions.md) and each MCP
server; chips above it show only the switched-on tools or one of those
sources),
**tool arguments** (what
a tool row shows next to the tool name: a per-tool summary such as the
command, the path or the pattern, or every argument as `key=value`), and
**super collapsed** (a run of two or more collapsed tool and thinking rows
shows as one summary line with a step count instead of its header rows).

### Color themes

"Color theme" in the same dialog lists the default theme, the built-in ones
(Tokyo Night, Catppuccin, Gruvbox, Nord, Rosé Pine and
Nezumi) and your own, each with a color strip (light page, its text, dark
page, accent); pick one to use it. Every theme has a light and a dark side,
so the mode switch keeps working.

A theme sets:

- the hue and chroma of the gray and accent scales and the status colors
  (success, warning, danger: status dots, usage meters, errors);
- the page background of light and dark mode, with the sidebar a chosen step
  brighter or darker than it;
- the background of added and removed diff lines (in the chat, the diff
  view and the terminal: a color for each and how strongly they stand out;
  unset, they follow success and danger);
- the **palette**: exact colors per mode for text, sidebar, surfaces,
  borders, accent, status and code highlighting. Unset ones are derived from
  the settings above and shown dashed;
- the **fonts** for text and for code, as family names (`Inter`,
  `"IBM Plex Mono", monospace`). Only fonts installed on the device show; a
  missing one falls back to the default. In Chromium, "Suggest installed
  fonts" adds your installed families to the suggestions;
- the spacing base, the gap between paragraphs, the type scale, the size of
  code (code blocks, tool output, diffs, the file viewer, the terminal) and
  the corner radius.

"Duplicate" on a built-in theme opens an editable copy of it in the editor
below the list; "Edit", or a click on the row of your theme in use, opens
that one; "New theme" starts from the defaults and a set of hue presets.
Each section of the editor starts with a preview of what it changes, and
folds; a folded one shows its values in its header. Changes apply to the
page as you make them. "Save theme" keeps the result under the name you
typed, "Delete" removes it, and closing the dialog drops unsaved changes.
"Change color theme…" in the command palette switches between themes.
Your themes are per [user](#what-follows-you), up to twenty.

### What follows you

These choices belong to your [user](server.md#users), not to the browser:
the theme and your color themes, the appearance settings, which sidebar
groups are collapsed, which home cards you switched off, the
model new chats start with, your [workspaces](workspaces.md) (not the
selected tab, which is each window's own), and your recently used commands
and skills. So do
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
