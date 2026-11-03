# Changelog

What changed in each Xi release, newest first.

## 0.6.0 - 2026-11-03

### Added

- Chat settings
  - Model and yolo mode for this chat, for new chats, or for all open chats
  - An agent profile's `:yolo false` turns yolo mode off in its chats
  - The yolo flame shows when yolo is off too, and says when yolo is also on for new chats

- Workspaces
  - `:workspace/switch` switches to the other tab when only two are open
  - `:workspace/new` opens the workspace list, so you can reopen a closed workspace
  - Workspace tabs in the sidebar drawer on narrow screens

- Agent blocks
  - Dial blocks: a panel of live controls for tuning values
  - A sub-agent's blocks show in its own view

- Chat
  - Run a shell code block in the terminal from its menu
  - Crop and highlight attached images in the lightbox
  - Tools that run code show it with syntax highlighting
  - Tool headers show their arguments like a command line
  - The Ask button shows when the block under the pointer was written
  - Model and usage under the message box on phones too

- Palette
  - <kbd>Tab</kbd> on a session opens its actions
  - `/` completes every skill, recently used ones first

- Agent tools
  - `write` with `append: true` adds to the end of a file
  - A write that was cut off keeps what it had written, and the next turn finishes it with append

- clj tool
  - Evals running past 60 s continue in the background, and the agent waits for them or aborts them
  - `fs/read-link`, `fs/last-modified-time` and helpers for file times

- Permissions
  - Asks before adding login keys, startup entries, crontabs or reverse tunnels, also in yolo mode
  - Shell rc file bundles you can turn on in `:rules`

- Extensions
  - Stop sub-agents and reply to them
  - Name a new chat up front with `:chat/start :name`
  - `(today)` in `xi.api.time` gives the local date
  - `:available?` hides a tool from chats it doesn't apply to
  - Pages from browser halves copy text and have their own context menus

### Changed

- On touch, tap send to queue and hold to steer

### Removed

- Plan mode. Use rules to make a chat read-only

### Fixed

- Permissions: `:command` rules no longer match commit messages and PR bodies

- Steering works while a permission question is open

- Models picking up a Claude session can call Xi's tools

- Workspaces
  - A tab keeps the extension page or home page it showed
  - Switching workspaces no longer adds the current chat to the new one

- Sub-agents
  - Their cards stay at the point where they were started
  - Deleting a message also removes the blocks from the turns it cuts

- Web client
  - Image files open as images, not as raw bytes
  - An error in the UI keeps the last screen instead of a blank page
  - <kbd>Tab</kbd> in `/` suggestions completes the command instead of running it
  - An expired sign-in shows the signed-out card, not a raw error
  - An edge swipe doesn't open a drawer while the palette is open
  - Moving the mouse no longer highlights a second menu row while you use the keyboard

- Python strings, `def`/`class` and imports get syntax highlighting

- clj tool: `Long/parseLong`, `Integer/parseInt` and `Double/parseDouble` reject input the JVM rejects

## 0.5.0 - 2026-10-26

### Added

- Windows
  - Split a chat into windows
  - Windows as sidebars
  - Undo and redo layout changes
  - On narrow screens one window at a time, sidebars as drawers

- [Workspaces](https://xi-agent.com/docs/workspaces/)
  - Tabs that narrow Xi to a set of projects
  - Make one from the palette (<kbd>Ctrl+K</kbd>)
  - Or per project, from `:workspaces` in `config.edn`

- Agent blocks
  - Agents show checklists, choices and small layouts you act on
  - Agents draw their own layouts as hiccup, sanitized
  - MCP Apps: tools with a UI show their view in the chat
  - Extensions add their own block kinds
  - Open a block as a buffer, move between blocks with the keyboard

- Sub-agents in the chat: ask, steer and reply in place

- Steering
  - <kbd>Alt+Enter</kbd> steers a running turn
  - On touch, tap send to steer, hold to queue
  - Steer a queued message into the running turn

- Providers and accounts
  - Sign in to several accounts of a provider and switch between them
  - `/account` and `xi account` list, switch and sign in to accounts
  - Accounts page in the web client
  - Every model provider runs as its own program, Claude included
  - `:providers` in `config.edn` picks them, from a directory or npm
  - Write your own with the provider protocol or `@xi-agent/provider-kit`
  - No runtime dependencies; Claude's SDK installs on first use
  - Save, resume and `/tree` work for Ollama, Codex and OpenCode

- CLI
  - `xi start` runs a background server and opens the web client
  - `xi server --no-pairing` admits every client

- Diff viewer
  - Stacked and split layouts
  - Explain and Modify answer inline

- Color themes
  - Tokyo Night, Catppuccin, Gruvbox, Nord, Rosé Pine and Nezumi

- Chat
  - Rename a chat from its menu, with suggested names
  - Copy or share a chat's link
  - Find the links and files a chat mentions from the palette
  - Model and usage under the message box on wide screens
  - Copy a table as Markdown or for a spreadsheet
  - Hex and `rgb()` colors show a swatch
  - Copy one line of a code block from its menu
  - Attachments as cards, editable after sending
  - Slash commands ranked by use

- Configuration
  - Pick per tool which tool blocks start collapsed

- Extensions
  - Load errors show in the UI instead of on stderr
  - [Palette menus and drill-downs](https://xi-agent.com/docs/extensions-reference/#browser-halves), also for [`xi.api.dialog/select`](https://xi-agent.com/docs/extensions-reference/#xiapidialog)
  - [`xi.api.store`](https://xi-agent.com/docs/extensions-reference/#xiapistore), a key-value store
  - Alerts, chips, button groups and skeletons on extension pages

- Skills
  - A `$SKILL_DATA` directory for each skill's data

### Changed

- Default port is 1414 (HTTPS 1443); `XI_PORT=7474` keeps the old one

- Project tasks
  - `run_task` replaces the `bb` tool, for bb, npm, pnpm, yarn and deno
  - Rules with the old names (`:tool :bb`, `:bb-trusted`) are refused until `xi migrate` updates them

### Fixed

- Permissions
  - `:dir` rules match whole path components, with `..` and symlinks resolved
  - `:guard :always` asks can no longer be overridden
  - Every pending ask shows in its own tool block
  - Answering one of several asks no longer jumps the chat
  - Sub-agents ask for their gated tool calls

- Scroll jumps on iPhone when opening or scrolling a chat

- Reopening the web client reconnects at once and loads faster

- Diffs
  - Diff buffers keep their state when you switch away
  - Session edits open after their directory moved
  - Files from other repos open from session diffs

- Resumed sessions keep their file attachments

- Switching chats no longer carries over the last chat's scroll position

- Copy path in a file tab copies the absolute path

- Home-relative paths in inline code open their file

- <kbd>Escape</kbd> closes the image viewer; select dialogs can be dismissed

- Fenced pastes in the prompt can be undone

- `ext_reload` takes effect mid-turn

- OpenAI-compatible API errors include the response body

- Collapsed skill prompts can be deleted

## 0.4.0 - 2026-10-15

### Added

- Terminal
  - Terminal buffers in the web client
  - Key mode: the program gets every key, Ctrl+` or Alt+\ leaves
  - Programs can prompt their chat and start sub-agents
  - Programs can run Xi view actions, copy to the clipboard and show tooltips
- Yolo
  - `/yolo` answers every permission ask in a chat
  - Sane and relentless levels; sane still asks before secrets and hard-to-undo actions
  - Turning it on allows the asks already open
- Sub-agents
  - Ask a sub-agent about selected diff lines, answered inline
  - `/subagent` command, also for running queued prompts
  - Open a sub-agent in place of the chat, like a buffer
  - Sub-agents can read the parent chat (`parent_history`)
- Keyboard
  - Link hints (`:hints/show`) click buttons and links
  - Vim-style jumplist with Ctrl+O / Ctrl+I, buffers included
  - Jump between the agent's answers with `:answer/prev` / `:answer/next`
- Files
  - File links in chat messages open as buffers
  - Relative links in a viewed markdown file open in the viewer
- Palette
  - Start a chat in the background, images included
  - Close the buffers of every session
- Permissions
  - `:task` rules match a project's bb, npm, yarn, pnpm and deno tasks
  - "Allow for this chat" answer on permission asks
  - The agent can write a repo's `.xi/rules.edn` after you confirm
  - Read-only git in another repository runs without asking
- Appearance
  - Show every tool argument in the block header
  - Color themes set the code size and diff line colors
- Extensions
  - Read the chat user's clock and location from their browser (`xi.api.device`)
  - Block menus and file-header entries like "Open in vim"
  - Clickable tool views
- Sessions
  - `session_view` tool to read a past session
  - `session_search` with an empty query lists the newest sessions
  - Agent profiles search only their own sessions and get a compute-only `clj`
- Release button when a chat waits on another chat's git lock
- Opened blocks are remembered per session
- The review canvas opens as a chat buffer

### Changed

- Keyboard
  - Alt+U goes to pending permission asks first
  - Alt+J/K skip buffer rows in the sidebar
- Appearance dialog split into Theme and Chat panes
- The buffer palette lists the most recently viewed first
- A lone tool or thinking block is no longer collapsed
- The 7-day usage reset counts down in days and hours

### Fixed

- Palette
  - Snippets, commands and search pages could open empty
  - Skills didn't show after they loaded
  - Parked draft chats were missing
- Sidebar
  - Cards could open the wrong session after a reorder
  - Context menus closed while a chat was streaming
- Files opened from the file finder were missing from the chat's buffers and gone on reload
- After a server restart a chat could be blocked by its own git lock
- Login refreshes left "Reply with: ok" sessions behind
- A usage limit showed as three error cards
- The composer lost focus when a render rebuilt it
- `clj` refused reads of `~/.config/xi/sessions` when the path was built at runtime

## 0.3.0 - 2026-10-09

### Added

- Custom color themes in the Appearance dialog, built from a page color
- Switch color themes from the Ctrl+K palette
- Set your own keyboard shortcuts with `:keys` in `config.edn`, and see them all in a shortcut list
- Navigate mode in the web client: `j`/`k` scrolling, `gg`/`G`, and a Doom-style `SPC` leader
- Hold Alt to see the shortcut on buttons and palette rows
- Home dashboard that starts a chat in any project
- `/usage` page for Claude, Codex, Ollama Cloud and OpenCode accounts
- A chat can keep several buffers open (diffs, files, system prompt) and reopens on the one you left
- Find the files a chat edited
- Browse a repo's git log from the palette, or with `/log`
- Pin chats so they stay in Recent
- Search all sessions from the bottom of the palette
- Find files in any project from the palette
- A chat's sub-agents are listed under it in the sidebar
- Retry and Switch model buttons on error cards
- Explain button on permission asks
- Allow all of a tool call's asks at once
- `/rules save` moves a chat's granted rules into the repo
- New rule match keys `:user`, `:host`, `:installed`, and `:path` on shell commands
- `:hint` rules that add a note to the decision below them
- Default rules ask before environment variables or secrets reach the model
- `process/poll-url` waits for a URL without asking for approval
- Drop files onto a chat to attach them
- Markdown diffs render as markdown, with a toggle to show code
- Shortcuts for Always (Alt+S), Allow repo writes (Alt+Shift+A) and Prune all (Alt+Shift+P)
- Browser tab title shows the chat or page in view
- Open a file or copy its path from a diff's file header
- Interrupted chats and their queued prompts continue after a server restart
- Extensions can keep room state across restarts, add palette items and message other users
- Demo extensions (notes, ping, hn), turned on with `:demo-extensions`
- `:agents-ignore` project setting skips a repo's own AGENTS.md files
- `:projects` can come from a `projects-config.edn` next to `config.edn`

### Changed

- Read-only commands only run without asking when their arguments are safe
- Alt+U steps through chats waiting on you, then unread, then running ones
- Alt+J/K moves through every visible sidebar row
- Opening an older chat leaves it in Earlier
- Escape closes the palette from any page
- Switching chats is instant
- Permission asks show two buttons, Deny and Allow, each with a menu of answers
- Enter submits the skill form

### Fixed

- `rm` and `mv` on a symlink deleted the target instead of the link
- Large `sh` and `curl` output was cut off before it could be parsed
- Several chats running after a reboot logged you out of Claude
- `--no-store` saved the transcript when `~/.claude` didn't exist yet
- User extensions didn't load in `xi prompt`
- Session titles could be set to an error message
- Reopened chats showed API failures as plain text instead of error cards
- A denied or interrupted ask lost its tool block and diff
- The model didn't learn why a mid-script path was denied
- `/diff` left out edits outside the chat's directory
- Time spent waiting on a permission ask counted as the tool's run time
- File finder queries with spaces found nothing
- Escape in the palette also closed the open buffer
- Drilling into a project could leave the palette empty
- Typing lagged on long chats
- Large diffs froze the browser tab
- Line breaks in your messages were lost
- Opening a missing file in the web client failed silently
- Lua files got no syntax colors
- A TUI crash left the terminal in raw mode

### Removed

- The viewer-mode setting: tool calls always fold into groups

## 0.2.0 - 2026-10-06

### Added

- Users are now a first-class concept: every connection belongs to a user (`root` by default), and everything below follows from that.
  - Pick a user with `--user` or `XI_USER`, or assign one to a paired device with `xi clients user`.
  - Declare users in `config.edn` with `:users`, a map of user id to an optional display name and read-only metadata. Declaring users is optional.
  - Chats show who sent each prompt and who is in the room. In the web client, a user's avatar appears on the chats they are in and on the corner of their prompt bubbles.
  - The web client switches user from the avatar in the sidebar footer.
  - Settings belong to the user, not the device, and are stored on the server: theme, appearance, collapsed sidebar groups, preferred model, recent commands and skills, read markers and hidden chats. Settings you already had in the browser carry over on first connect.
  - Extensions can keep their own state per user, and tool calls know which user they are acting for.
- Extensions can add groups to the web client's sidebar and entries to each session card's context menu.
- Web client: unsent new chats stay as drafts in the sidebar.
- Web client: scroll up to load earlier messages instead of pressing a button.
- Web client: a chat whose last turn failed gets a red status dot on its card, the sidebar and the palette.
- Web client: a usage-limit card now reads as an error and has a button to continue the chat.
- Web client: expand snippet triggers with Tab in the composer.
- Web client: copy a session ID from the session card's context menu.
- `bb` tool blocks show the task name in their header.

### Changed

- Favorites are no longer built in. The stars, the Favorites view, the `/favorite` and `/favorites` commands and the `*` and `s` keys are gone from core. They now come from an extension in `~/.config/xi/extensions`, built on the per-user state, sidebar-group and menu-item hooks.
- Loading a saved session no longer prints a "Resumed: …" status line.

### Fixed

- A usage-limit or auth notice from Claude was shown as assistant text. It is now reported as an error.
- An outdated Claude usage reading showed a stale percentage. It now shows as unavailable.
- Blank new-session chats came back after every server restart.
- A chat that was never prompted couldn't be deleted while a client had it open.
- Nested lists in messages collapsed into one paragraph.
- Opening or switching chats no longer animates the scroll.
- Links in assistant messages are styled correctly again.
- Reset notes in the Claude usage popover are brighter.

## 0.1.0 - 2026-10-06

First public release.

### Added

- Terminal client and web client that join the same sessions. Start a chat in the terminal and continue it on your phone.
- A persistent Clojure REPL (the `clj` tool) in place of a shell tool, with file helpers, so the agent filters and aggregates in the runtime and only the result enters the conversation.
- Permission rules: a file of ordered allow, deny and ask rules, with repo rules over global ones. Denials carry a reason the model reads.
- Extensions written in ClojureScript, loaded from `~/.config/xi/extensions` and reloaded live, with tools, commands, key bindings and web pages.
- MCP support in both directions: Xi offers its tools to the model and pulls in tools from servers you trust.
- Outline-first code reading with tree-sitter for TypeScript, JavaScript, Python, Rust, Go, Clojure, Nix, Bash and CSS.
- Models through the Claude Agent SDK, Ollama, ChatGPT subscription (Codex) and OpenCode Zen.
- Device pairing by a four-digit code, an installable web client and an offline session list.
- A built-in project list, named agent profiles and a git lock that serializes staging across chats sharing a repo.
- The user guide, published together with a home page at xi.florianschroedl.com.
- The `xi-agent` npm package, with `bb package:serve` and `bb package:docker` to try it on a clean machine.
