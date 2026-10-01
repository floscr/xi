# 3a. `xi.api.chrome` — headless Chrome for user extensions

← [3. Move personal extensions out](03-port-personal-extensions.md)

Product search needs a real browser (Amazon blocks scripted HTTP on and off,
willhaben renders client-side), and the extension sandbox can't drive one. A
new capability lets a user extension drive a headless Chrome **only** for the
hosts its own map declares.

## Extension-facing API

```clojure
(ns product-search
  (:require [xi.api.chrome :as chrome]))

(def extension
  {:id :product-search
   :permissions {:chrome-driver {:hosts ["amazon.de" "media-amazon.com"
                                         "willhaben.at" "geizhals.at"]}}
   ...})

(chrome/visit ctx "https://www.amazon.de/s?k=usb"
              {:wait-for "document.querySelectorAll('…').length"  ; JS, polled until truthy
               :eval     "(() => ({items: […]}))()"              ; JS, JSON-able result
               :timeout-ms 15000})
;; → Promise<{:url <final url> :value <eval result, keyword keys>
;;            :timed-out? bool}>   (no :eval → :html of the document)
```

## Permission model

- `:permissions` becomes an allowed extension key. Validated at load:
  `{:chrome-driver {:hosts [<bare host> …]}}`. A host is a domain without
  scheme, port, path or wildcard and covers its subdomains (`amazon.de` →
  `www.amazon.de`). Anything else rejects the file with a reason.
- **The declaration is the grant.** The file only loads when the global
  rules file lists it under `:extensions`, so enabling it approves its hosts.
  No per-visit rules prompt (it would make the pi agent unusable, since nobody
  is there to answer).
- No declaration → `visit` rejects. URL host not covered → `visit` rejects
  before Chrome starts.
- Permissions are looked up by extension id in a registry the loader fills.
  They aren't read from the ctx the extension passes in, so it can't widen
  them itself.

## Enforcement inside Chrome (the browser may only talk to declared hosts)

`:eval` runs extension JS in the page, so without limits the browser could be
used to reach any host and get around `xi.api.http`'s `:net` gate.

- **DNS:** launched with `--host-resolver-rules="MAP * ~NOTFOUND, EXCLUDE h,
  EXCLUDE *.h, …"`. Every name outside the list fails to resolve. This covers
  fetch, XHR, WebSocket, subresources and redirects.
- **Requests:** CDP `Fetch` intercepts every request and fails the ones whose
  host isn't covered. This catches IP-literal URLs, which skip DNS, and yields
  a clear error when the top-level navigation is blocked.
- `--force-webrtc-ip-handling-policy=disable_non_proxied_udp` disables WebRTC
  UDP.
- Throwaway profile (tmp `--user-data-dir`, removed on kill), no cookies
  carried between launches.
- Known residual: a WebSocket to a raw IP address (`ws://1.2.3.4`) skips both
  DNS and `Fetch`. Documented.

## Lifecycle

- One Chrome per extension, since its resolver rules are fixed at launch. It
  starts lazily on the first `visit`, gets one tab, and visits run one at a
  time.
- Killed after 5 min idle, on `/ext reload` (permissions may have changed),
  and on server shutdown (`:on-shutdown` of `xi.ext.user/server-extension`).
- Binary: `XI_EXTENSION_CHROME`, else common paths, else
  `google-chrome-stable` on PATH (today's `product_search/cdp.cljs` logic).

## Code

- `src/xi/browser/cdp.cljs` — the hand-rolled CDP client from
  `xi.ext.product-search.cdp`, made generic: launch with extra flags, `send!`,
  CDP event subscription (needed for `Fetch.requestPaused`), kill.
- `src/xi/api/chrome.cljs` — `visit`, host matching, permission registry,
  per-extension sessions, idle reaper.
- `src/xi/ext/user.cljs` — expose `xi.api.chrome`; `:permissions` in
  `allowed-keys` + validation; register permissions on load/reload; kill
  sessions on reload/shutdown.
- Tests: host normalisation/matching, permission validation (good/bad shapes),
  resolver-rules flag builder, `visit` refusals (no declaration, foreign
  host). No real Chrome in tests.
- Docs: `docs/user-extensions.md` (capability table, `:permissions`, browser
  enforcement), `docs/config.md` (`XI_EXTENSION_CHROME`; drop
  `XI_PRODUCT_SEARCH_CHROME` / `XI_AMAZON_CHROME`).

## Then (rest of section 3)

- [ ] Product search → `~/.config/dotfiles/config/xi/extensions/product_search.cljs`
      on `xi.api.chrome`. Delete `src/xi/ext/product_search/`, its config
      entry and docs rows. Delete the interim `config/xi/product-search/`
      (bun script + nix wrapper).
- [ ] Dotfiles: add `product_search.cljs` to the desktop `rules.edn`
      `:extensions` and to `xi-agent.nix` `extensionFiles`. The pi gets
      `XI_EXTENSION_CHROME` instead of `XI_AMAZON_CHROME`. The xi-agent rules
      file is only seeded when missing, so a pi with an existing one needs
      `:extensions` updated (provision must handle that).
- [ ] Remove render (`xi.ext.render`, config entry, test, docs; mcp.edn example
      stays in `docs/mcp-servers.md`).
- [ ] Remove dictation (`xi.ext.dictation`, `xi.config/client`, mentions).
- [ ] Remove image-graph (server + web halves, gemini, config, docs, CSS).
- [ ] GTD: the extension is already gone (ff8032d). Reword the GTD mentions in
      `ws.cljs`, `views.cljs` and `docs/server.md`. Keep `/api/rooms` +
      `/api/rooms/status`, because the dotfiles org-server still calls them.
- [ ] Personal-agent mode: later (its prompt/tool allowlist still names the
      product-search tools).
