# Changelog

What changed in each Xi release, newest first.

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
