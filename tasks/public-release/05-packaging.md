# 5. Packaging

← [Public release](../public-release.md)

## Context

- No README, no LICENSE. `package.json`: `"private": true`, `0.1.0`.
- Git deps on a personal Gitea:
  - `com.example.git/clj-ui-framework` — `deps.edn:5-6`, `bb.edn`
    (`dialkit:css`), `scripts/gen-dialkit-css.clj:33-36`
  - `tmux-dev` — `bb.edn:2`
- Nix-only install paths:
  - Claude CLI pin (`packages/providers/anthropic/nix/`, `bb claude:build` /
    `claude:update`). `runner.mjs:185` prefers the pin, else `claude` on PATH,
    `XI_CLAUDE_CLI_PATH` overrides — the PATH fallback is untested.
  - `xi-treesitter` — `nix-build packages/xi-treesitter -o ~/.config/xi/treesitter`.
- `src/xi/tui/clipboard_image.cljs:59,70` — X11 `xclip` only.
- History: 1038 commits containing personal paths, host names, planning notes.

## Steps

- [ ] LICENSE: pick one (MIT / EPL-2.0 / AGPL…). Check it's compatible with
      clj-ui-framework's and the vendored grammars' licenses.
- [ ] README: what xi is, requirements (Bun, Babashka, Java for shadow-cljs,
      Claude CLI login or `ANTHROPIC_API_KEY`), quick start (`bb build`,
      `bb serve`, `xi`), link to `docs/`.
- [ ] Make clj-ui-framework and tmux-dev reachable: public mirror (GitHub /
      Codeberg) and update the `:git/url`s, or vendor them. The dependency
      coordinate can stay; the URL must resolve anonymously.
- [ ] Non-Nix Claude CLI: document `npm i -g @anthropic-ai/claude-code` (or
      `XI_CLAUDE_CLI_PATH`); make `bb claude:build` / `claude:update` print a
      clear message when `nix` is missing.
- [ ] Non-Nix xi-treesitter: a build path without Nix (`cargo`/`cc` script or
      prebuilt release asset), and confirm reads degrade to plain text when
      the CLI is absent.
- [ ] Clipboard images: add `wl-paste` (Wayland) and `pbpaste`/`osascript`
      (macOS), or document X11-only.
- [ ] `package.json`: keep `"private": true` unless publishing to npm; set
      `license`, `repository`.
- [ ] History: decide — publish as-is (after the [secret scan](01-security-defaults.md))
      or squash to a fresh root commit for the public remote while keeping the
      full history privately.

## Done when

A stranger can follow the README without access to the Gitea or Nix.
