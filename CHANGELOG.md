# Changelog

What changed in each Xi release, newest first.

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
