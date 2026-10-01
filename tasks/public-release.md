# Public release

Goal: anyone can clone xi, build it on a stock machine (no NixOS, no
dotfiles, no Tailscale) and run it safely. Everything personal either moves to
the dotfiles as a user extension, sits behind documented config, or goes.

Audit baseline: master @ c1e9797 (2026-10-01). Git history scanned for known
key formats (Anthropic, OpenAI, GitHub, Render, Gemini, Pushover): clean, and
no `.env` / credential file was ever committed.

Work the sections in order — 1 is a safety fix, 6 is the release gate.

## 1. Security defaults → [details](public-release/01-security-defaults.md)

Personal-agent mode admits every client and skips `/api/rooms` auth, while
Bun binds all interfaces.

- [ ] Pairing required in every mode; explicit opt-out only
- [ ] Choose the bind address (default loopback, or document all-interfaces)
- [ ] Secret scan with gitleaks / trufflehog before publishing

## 2. Hardcoded personal environment → [details](public-release/02-hardcoded-environment.md)

Absolute `/home/floscr/…` paths, the dotfiles `bb.edn`, the xmonad `wm` CLI,
`tailscale ip -4` in bb tasks, host-named icons.

- [ ] chrome-mcp: no default personal bins; WM scoping off unless configured
- [ ] System prompt / review / snippets: drop the dotfiles `profile:*` calls
- [ ] bb serve tasks: work without Tailscale
- [ ] Icon variants: generic, not host names
- [ ] theme-mode file: documented as optional

## 3. Move personal extensions out → [details](public-release/03-port-personal-extensions.md)

`render`, `product-search`, `dictation`, `image-graph`, personal-agent mode.

- [x] render → documented `mcp.edn` entry, extension deleted
- [x] product-search → dotfiles user extension on the new `xi.api.chrome`
- [x] dictation → removed
- [x] image-graph → removed
- [x] GTD hook → references removed; `/api/rooms` kept as a generic API
- [ ] personal-agent mode → product feature or private (decide later)

## 4. Stale config, docs and leftovers → [details](public-release/04-stale-config-docs.md)

- [x] Delete `.env.example` (Pushover only, dead since 49a385c)
- [x] Refresh the `xi.config` docstring
- [x] Fix the `deftui-opt` undeclared-var build warning
- [x] Scrub personal paths/hosts/tools from docs, comments, test fixtures
      (what's left is code defaults, tracked in section 2 / 5)
- [x] Make AGENTS.md generic; `preview-join.html` deleted; `tasks/` stays until
      the release gate, then `git rm -r tasks/` (see section 6)

## 5. Packaging → [details](public-release/05-packaging.md)

- [ ] README + LICENSE
- [ ] Public sources for the `clj-ui-framework` and `tmux-dev` git deps
- [ ] Install path without Nix (Claude CLI, xi-treesitter)
- [ ] Clipboard images beyond X11 `xclip`
- [ ] Publish the history as-is or squash to a fresh root

## 6. Clean-machine check → [details](public-release/06-clean-machine-check.md)

- [ ] Fresh user, non-Nix distro, no dotfiles: clone → build → test → run
      TUI, server, web, `xi prompt`

## Review

- **Section 4** done in `650f3e4` except the items listed under "Left for
  later" in [04-stale-config-docs.md](public-release/04-stale-config-docs.md)
  (chrome-mcp docs follow section 2; `tasks/` is decided at the gate; the
  `PUSHOVER_` leftovers in `xi.env`).
- Another session was working on section 3 in the same tree while this ran
  (product-search / image-graph / dictation / render deletions staged,
  uncommitted). Commit section-4 changes with a temp index or path-limited
  commits so those aren't swept in.

(fill in the rest when done)
