# Screenshots

README/marketing screenshots of the web client, rendered against **fabricated
demo data** so they never leak real sessions and stay reproducible.

Committed images live in [`screenshots/`](screenshots/):

| File | View | `?demo` value |
|---|---|---|
| `screenshots/sessions.png` | Session list | `?demo=sessions` |
| `screenshots/chat.png` | Chat room mid-conversation | `?demo=chat` |

## How it works

The web client seeds a static, fully-populated view when loaded with a
`?demo=<view>` query param instead of connecting to the WS server. The demo
branch in `xi.web.core/init!` renders once (no transport, no live data) with
data from [`src/xi/web/demo.cljs`](../src/xi/web/demo.cljs).

Supported views: `sessions` (session list), `chat` (a coding conversation).
Any other value falls back to the session list.

To change what the screenshots show, edit the demo data in
`src/xi/web/demo.cljs` — `demo-sessions` for the list, `demo-history` for the
chat timeline. The shadow-cljs watch recompiles on save; just reload the page.

## Regenerating

Screenshots are captured at **iPhone size** (390×844) at 3× device pixel ratio
(→ 1170×2532 retina PNGs) using the Chrome DevTools MCP against the running
server:

1. Make sure the server is up (`bb check`; if 7474 is free, `bb serve`).
2. Emulate the viewport: `390x844x3,mobile,touch`.
3. For each view, navigate to `http://localhost:7474/?demo=<view>` and take a
   full-page screenshot into `docs/screenshots/<view>.png`.

The demo render leaves the color scheme on `auto`, so emulating light/dark in
DevTools switches the theme without any code change.
