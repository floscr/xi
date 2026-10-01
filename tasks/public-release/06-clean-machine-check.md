# 6. Clean-machine check (release gate)

← [Public release](../public-release.md)

Run after sections 1–5. Use a throwaway environment that has none of the
personal setup: a container or VM on a non-Nix distro (e.g. Debian/Ubuntu),
fresh user, no `~/.config/dotfiles`, no `~/.config/xi`, no Tailscale.

## Steps

- [ ] Install only what the README lists; clone from the public remote.
- [ ] `bb build`, `bb web:build`, `bb test` — all green, no warnings.
- [ ] `bb serve` starts and prints a usable URL without Tailscale.
- [ ] Web client: pairing flow works; a turn runs with tools; chrome-mcp
      either works with a stock Chrome or is quietly unavailable.
- [ ] TUI (`xi`) — run by a human, not the agent: a turn, `/rules`, `/ext
      list`, theme detection falls back to dark.
- [ ] `xi prompt "…"` one-shot works.
- [ ] Personal-agent mode (if kept) refuses unpaired clients.
- [ ] Optional deps missing (whisper-stream, xi-treesitter, xclip, gh,
      GEMINI_API_KEY): each feature reports itself unavailable, nothing
      crashes, no 10 s stalls per session.
- [ ] A user extension from
      [docs/user-extensions.md](../../docs/user-extensions.md) loads and runs.
- [ ] Grep the built server log for `floscr`, `dotfiles`, `hetzner`.

## Done when

Every box above passes; record findings in the
[Review](../public-release.md#review) section.
