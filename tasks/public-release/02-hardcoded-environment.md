# 2. Hardcoded personal environment

← [Public release](../public-release.md)

## Context

| Where | What |
|---|---|
| `src/xi/ext/chrome_mcp/launch.cljs:30` | Launch bin defaults to `/home/floscr/.config/dotfiles/bin/browser` |
| `src/xi/ext/chrome_mcp/wm.cljs:28,35` | `/home/floscr/.config/dotfiles/bin/wm`, `/etc/profiles/per-user/floscr/bin/wmctrl`; per-session scoping assumes the xmonad `wm` CLI |
| `src/xi/system_prompt.cljs:12-16,133` | `bb --config ~/.config/dotfiles/modules/scripts/bb.edn profile:agents-prompt <cwd>` on every session (10 s timeout; fails quietly) |
| `src/xi/ext/review.cljs:26` | Same dotfiles `bb.edn` (`profile:review-prompt`) |
| `src/xi/ext/snippets.cljs:25` | Same dotfiles `bb.edn` (`profile:snippets`) |
| `bb.edn:204,213,223,228,317` | `dev:url`, `serve`, `serve:restart`, `serve:personal*` shell out to `tailscale ip -4`, which throws without Tailscale |
| `src/xi/server/ws.cljs:272-340` | Icon variants `personal` / `hetzner` (+ `resources/public/apple-touch-icon-{personal,hetzner}.png`) |
| `src/xi/tui/theme_mode.cljs` | Reads the dotfiles `theme-mode` state file |
| `src/xi/ext/product_search/cdp.cljs:51`, `src/xi/env.cljs` | NixOS paths (`/etc/profiles/per-user/…`, `/run/current-system/sw/bin`, `/nix/store` scrubbing) |

## Steps

- [ ] chrome-mcp: default the launch bin to whatever Chrome/Chromium is on
      PATH; drop the floscr defaults for `XI_CHROME_WM_BIN` /
      `XI_CHROME_WMCTRL_BIN`. Turn workspace scoping on only when the WM bin
      is configured and exists; otherwise behave like `XI_CHROME_NO_SCOPE`.
      Set the old values in the dotfiles (env or rules/config).
- [ ] Per-project prompt / review prompt / snippets: replace the dotfiles
      `profile:*` calls with a config xi owns. Options:
      - per-repo `.xi/` files (`agents.md`, `review.md`, `snippets.edn`), or
      - a user-configured command in `~/.config/xi/` that xi runs (the dotfiles
        keep `bb profile:*` behind it).
      One mechanism for all three; document it in
      [docs/config.md](../../docs/config.md).
- [ ] `bb.edn`: one `host-ip` helper that tries `tailscale ip -4` and falls
      back to `localhost` (or a `XI_PUBLIC_HOST` override); use it in all five
      tasks.
- [ ] Icons: rename the variants to neutral names (e.g. `alt`, `warm`) or
      allow `XI_ICON` to be a file path. Keep the hetzner icon in the dotfiles.
- [ ] theme-mode: keep the file lookup, but describe it in the docs as a
      generic optional state file, not "the dotfiles" one.
- [ ] `env.cljs` / `cdp.cljs`: check the NixOS branches are no-ops elsewhere
      (covered by the [clean-machine check](06-clean-machine-check.md)).

## Done when

`grep -rn "floscr\|dotfiles\|tailscale" src bb.edn` hits only comments that
explain an optional integration.
