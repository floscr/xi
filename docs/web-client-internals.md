# Web client internals

Using the web client is in the guide: [the web client](guide/web-client.md).
This page is how it is built. Offline cache, reconnect and the transfer
skips are in [web-offline.md](web-offline.md); UI components in
[frontend.md](frontend.md); the demo server for self-tests in
[demo.md](demo.md).

## Architecture

The browser runs **the same pure core as every other mode**: `xi.core.app`
in `:client` mode, wired through `xi.client.ws-transport`, with
[Replicant](https://github.com/cjohansen/replicant) as the renderer. The
browser only renders state and collects input.

```
Browser: create-app (:mode :client) · one atom · pure handlers · taps
   ▲ mirror (:remote? events, effects stripped)      │ render
   │                                                 ▼
 ws-transport (forward local events → [:ws/send])   Replicant ← views.cljs
   │ WebSocket, transit event maps (xi.wire)
   ▼
 xi server (:7474) — rooms, agent, sessions
```

- The browser merges the same handler maps the server uses (`core-handlers`
  + `agent` + `commands` + `compaction`) plus web-local handlers (router,
  compose, lightbox, unread). Effects are stripped on mirror anyway.
- Locally originated events are sent to the server; the server broadcasts
  every room event (sender included) and the client applies them through
  the same reducers, seeded by the `:room/joined` snapshot.
- Served by the same Bun server that hosts the WS endpoint, from
  `resources/public` (SPA fallback to `index.html`). `bb web:build` compiles
  the `:web` build; the dev watch hot-reloads it.

## Views

- **Launch card**: the first timeline block of every chat (logo, model —
  click to switch —, cwd, loaded AGENTS.md files); ordinary timeline content
  (`launch-header`), not an empty state.
- Tool and thinking blocks are `<details>`; Replicant only writes changed
  attrs, so manual toggles survive re-renders. **Viewer mode**
  (`group-viewer-items`) folds runs of tool/thinking posts into
  `.viewer-tool-group`; a run breaks on text or on a pending permission ask
  (always expanded). **Super collapsed** (`xi.web.viewer-group`, pure) folds a
  fully collapsed group into one summary row. Appearance layering:
  `xi.web.appearance` defaults ← `xi.config/appearance` ← the user's
  overrides (per-user state, see below; `localStorage "xi/appearance"` is
  its cache).
- **Per-user UI state** (`xi.user-state`, `xi.web.user-state`): theme,
  appearance, collapsed sidebar groups, preferred model and recent
  commands/skills live on the server per user
  (`~/.config/xi/state/users/<user>.edn`, `xi.user-state.store`);
  localStorage is the instant cache. `xi.web.user-state/bindings` maps each
  registry key to its state path and the effect that mirrors it. The server
  sends `:user-state/state` after `:auth/ok` (server wins; keys it lacks are
  seeded up from the cache) and `:user-state/changed` to every device of the
  user on a write. A change is sent with `user-state/set-effect` next to the
  existing `:cache/*` effect; the startup theme dispatch is `:init? true` so
  it never overwrites the server's. The cache records its owner
  (`xi/user`): a different user on a shared browser resets to defaults
  instead of inheriting or seeding the previous user's values.
- **Timeline virtualization**: last 60 entries render; "Show earlier" adds 40
  (`:web/timeline-window`, reset on navigation).
- **Code-block menu**: right-click / tap → Copy; on Read/Write/Edit blocks
  View file (`:file/open`); on blocks rendering a change View diff
  (client-local `:ui/diff-open`, no git run). Blocks carry
  `data-file-path` / `data-diff-path` / `data-diff-text` for the delegated
  listener in `xi.web.core`; `xi.diff/tool-diff->unified` converts the tool
  diff format.
- **Permission ask focus**: an ask carrying a `:target {:arg :code :ranges}`
  (clj gate asks) renders the code in segments (`code-focus-segments`), the
  ranges at full contrast and the rest `.code-muted`; hover lifts the muting.
- **Run timer**: a running tool block shows elapsed time after 2s; the
  tool-start event's `:at` becomes the entry's `:started-at` and the label
  repaints its own DOM text every second (`run-timer`, `replicant/on-mount`)
  because a stalled call emits no events.
- **Error cards**: `xi.error-info/describe` (pure) classifies agent errors
  (rate limit with reset time and usage meter, auth, billing, overloaded,
  network) into a plain-language card with a Technical details toggle;
  unrecognised errors keep the raw `[Error]` line (`error-card`).
- Images: paste / picker, client-side resize via `xi.image`, lightbox
  (`:web/lightbox`). Markdown via `xi.markdown.hiccup`, highlighting with the
  bundled browser grammars ([syntax-highlighting.md](syntax-highlighting.md)).
- Auto-scroll pinned to the bottom unless scrolled up. Sending from a cached,
  not-yet-joined session stashes the message (`:web/pending-submit`) and
  fires it after `:room/joined`.

## Routing, unread, visibility

- The router lives in the atom (`:web/route`): `:route/navigate` is a pure
  handler emitting `[:history/push]` and room join/leave dispatches
  (`xi.web.router`); `popstate` re-dispatches with `:replace? true`. Deep
  links `/chat/:sid` hydrate from cache, then join over WS. Extension routes
  are consulted before the built-in ones.
- Unread: `:session/counts` → `:session/counts-result` (`:web/response-counts`)
  compared with the lobby's `:read` (the server's markers for *this user*, see
  architecture.md) and `:web/watched` (a localStorage overlay for an instant
  clear); viewing marks read (`:session/mark-read` + `:cache/watch`). A browser
  that connects as a different user than it cached for drops `:web/watched`
  with the rest of the cached user state (`xi.web.user-state/reset-all`).
- `visibilitychange` dispatches `:client/update {:visible? …}` so the server
  can suppress notifications while a visible client is attached.

## Keyboard

`xi.web.keymap` dispatches every keydown to view- and mode-scoped bindings
registered in `xi.web.core/install-keybindings!`. Mode is `:insert` while a
**visible** text field has focus, else `:normal`; focus stranded in a hidden
field counts as normal so shortcuts are never swallowed by an invisible
input. The user-facing table is in the guide.

## Web-only state keys

Never sent over the wire:

| Key | Contents |
| --- | --- |
| `:web/route` | `{:page … :session-id …}` |
| `:web/drafts` | compose drafts per session (`:new` before the first join) |
| `:web/compose-images` | staged attachments |
| `:web/timeline-window` | virtualization window |
| `:web/appearance`, `:web/appearance-config`, `:web/appearance-open?` | appearance overrides, the seeded config map, dialog open |
| `:web/lightbox` | open image src |
| `:web/watched`, `:web/response-counts` | unread tracking |
| `:web/cache` | hydrated per-session history for deep links |
| `:web/pending-submit` | message stashed until `:room/joined` |
| `:web/connected?` | transport status |
| `:web/nav-items` | extension nav entries, stored at init |
| `:user-ext/ui` | per-extension browser-only UI state (`:bind` inputs) |
| `:lobby` | rooms + sessions mirror (shared shape with the TUI client) |

## Source files

```
src/xi/web/
  core.cljs        entry: assembly, Replicant render, auto-scroll, listeners
  views.cljs       pure views: home, chat, compose, lightbox, error cards
  router.cljs      route parsing, :route/navigate, History API effect
  cache.cljs       localStorage offline cache (hydrate + persist tap)
  appearance.cljs  appearance settings layering
  keymap.cljs      view- and mode-scoped shortcuts
  viewer_group.cljs super-collapsed summaries (pure)
  user_ext.cljs, user_ext/  browser halves of user extensions (lazy :user-ext module)
  demo.cljs        fabricated data for the static ?demo=<view> render
src/xi/client/ws_transport.cljs   shared WS transport (forward+mirror, reconnect)
src/xi/server/ws.cljs             WS server + static file serving
resources/public/                 index.html, css/style.css, theme.css, ui-runtime.js
```
