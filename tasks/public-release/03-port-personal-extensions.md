# 3. Move personal extensions out

← [Public release](../public-release.md) · prior rounds: 74be76b, 49a385c
(plan: `git show 49a385c:tasks/todo.md`, Phase E / F)

## Context

User-extension tier (`~/.config/xi/extensions/`, sandboxed SCI, `xi.api.*`
calls checked by the rules engine) already took kb, browser-open, done-notify,
pushover, freesearch, web and github-code-search. Two limits that matter here:

- User extensions have **no TUI-process half**: `xi.cli/client-extensions`
  only instantiates `xi.config/client`; `xi.ext.user` loads server, mirror
  and web halves only.
- Built-ins can't be **shipped disabled**: there is no default-off flag, so
  "keep it built-in but optional" needs one.

## Candidates

### render
Seeds a disabled `:render` entry into `~/.config/xi/mcp.edn` plus a `/render`
status command (`src/xi/ext/render.cljs`).
- [ ] Add the entry to the dotfiles' `mcp.edn` (or document it as an example
      in [docs/mcp-servers.md](../../docs/mcp-servers.md)).
- [ ] Delete `xi.ext.render` + its config entry, tests, docs rows.

### product-search (amazon.de, geizhals.at, willhaben.at)
Drives a real Chrome over CDP (`product_search/cdp.cljs`). The sandbox has no
CDP/WebSocket access.
- [ ] Decide:
      - **user extension** that shells out (`xi.api.sh`) to a headless-Chrome
        script in the dotfiles (each command asks once, then `[a]lways`), or
      - **drop** it from xi.
- [ ] Remove `XI_PRODUCT_SEARCH_CHROME` / `XI_AMAZON_CHROME` from
      [docs/config.md](../../docs/config.md) afterwards.

### dictation
TUI-only (`xi.config/client`), spawns `whisper-stream`, models in
`~/.local/share/whisper-models`.
- [ ] Either add a `client-extension` half to the user-extension loader
      (process-local: keybinding, badge, `:editor/*` effects, `xi.api.sh`
      spawn — long-running spawn API needed), then port it, or
- [ ] keep it built-in but make it inert when `whisper-stream` is missing and
      document the dependency.

### image-graph (Gemini art graphs)
Large server + web half (node canvas). Web halves of user extensions are
sanitized hiccup with curated views, which likely can't host it.
- [ ] Decide: keep as built-in (needs the default-off flag below) or move to
      a separate repo/package.
- [ ] If keeping: add a default-off mechanism for built-ins (e.g.
      `:default-enabled? false` honoured by the extension manager), document
      `GEMINI_API_KEY` setup.

### personal-agent mode + GTD hook
`--personal-agent-only`, `~/.config/xi/personal-agent/<agent>/`,
`serve:personal*` tasks, `POST /api/rooms` + session-status polling used by
the dotfiles GTD service.
- [ ] Decide whether PA mode is a product feature. If yes, document it as such
      (no GTD references); if no, plan its removal separately.
- [ ] `/api/rooms` is generic and worth keeping — reword
      `src/xi/server/ws.cljs:737-752` and [docs/server.md](../../docs/server.md)
      without the GTD service.

## Outcome (2026-10-01)

- **render** — `xi.ext.render` and `mcp/ensure-registry-entry!` deleted;
  Render is the worked `:http` example in docs/mcp-servers.md.
- **product-search** — new capability `xi.api.chrome/visit` for user
  extensions (`xi.browser.chrome` + `xi.browser.proxy`): opt-in via
  `:permissions {:chrome-driver {:hosts […]}}`, each visit a
  `{:tool :browser :host …}` rules request (default ask), the browser's only
  network path a CONNECT proxy limited to the declared hosts, CDP over a pipe,
  throwaway profile. The extension lives in the dotfiles
  (`config/xi/extensions/product_search.cljs`), enabled + pre-allowed in
  `config/xi/rules.edn` and shipped to the pi by `modules/services/xi-agent.nix`
  (which now rewrites the agent's rules.edn on every start).
  `XI_AMAZON_CHROME` / `XI_PRODUCT_SEARCH_CHROME` → `XI_CHROME_BINARY`.
- **dictation**, **image-graph** — removed (with the dictation-only editor
  seams and the image-graph CSS).
- **GTD** — the remaining comments/docs reworded; `/api/rooms` and
  `/api/rooms/status` stay (the dotfiles org-server uses them).
- **personal-agent mode** — open. It still names the product tools in
  `system-prompt/PERSONAL_AGENT_PROMPT` and `registry/PERSONAL_AGENT_TOOLS`.

Open point: an extension's own code can pass a ctx with another `:extension`
id to any `xi.api.*` call and so borrow that extension's grants (and, for
`xi.api.chrome`, its declared hosts). Pre-existing for fs/sh/http; worth an
unforgeable ctx before release.

## Done when

`xi.config/server` contains only extensions a stranger would want, each
working or quietly inert without personal setup.
