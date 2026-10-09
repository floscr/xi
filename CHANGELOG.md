# Changelog

What changed in each Xi release, newest first.

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
