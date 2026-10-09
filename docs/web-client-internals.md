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
  (`group-viewer-items`, always on) folds runs of tool/thinking posts into
  `.viewer-tool-group`; a run breaks on text or on a pending permission ask
  (always expanded). **Super collapsed** (`xi.web.viewer-group`, pure) folds a
  fully collapsed group into one summary row. Appearance layering:
  `xi.web.appearance` defaults ← `xi.config/appearance` ← the user's
  overrides (per-user state, see below; `localStorage "xi/appearance"` is
  its cache).
- **Home dashboard** (`/`, `xi.web.dashboard`; the page is
  `views/dashboard-view`): a composer, then the `:dashboard-cards` of every
  web half in a grid. The cards hold fns, so they live in a registry beside
  the state (`register!`: built-in halves at init, user halves once loaded),
  like `xi.web.palette-items`. `visible-cards` is pure (hidden set, `:when`,
  `:order`); `load-tap` sends every non-hidden card's `:load` on entering
  the dashboard and on a reconnect while on it. The composer's `:home/start`
  (`xi.web.core`) opens a pending room in the picked cwd and runs
  `submit-pending` on it, the same path as typing into a new chat. The
  built-in cards are `xi.ext.home.web` (Active, Recent chats, Projects) and
  `xi.ext.usage.web` (Usage). `/projects` is the project list
  (`:dir :projects`).
- **Per-user UI state** (`xi.user-state`, `xi.web.user-state`): theme,
  appearance, collapsed sidebar groups, hidden dashboard cards, preferred model and recent
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
- **Buffers** (`xi.buffers`, cljc): the room's `[:ui :buffers id → buffer]`
  — diffs (`diff:<source>`), files (`file:<path>`), the prompt (`:prompt`)
  — and `[:ui :active-buffer]`. The list is room state (mirrors, survives the
  room's close parked per session on the server, see architecture.md); the
  active one is this client's: `:ui/buffer-switch` is a ws-transport
  local-ui event, and a switch a server flow dispatches for one client (a
  `/diff` reply, a file read) carries that client's `:client-id`, which the
  core reducer compares with `[:connection :client-id]` (from `:auth/ok`).
  `room-joined` resets the snapshot's active buffer to the chat. The topbar
  `buffer-menu` (a `.tab-pill` trigger + `ui.popover`) names the view in front
  and lists the chat, every buffer (× → `:ui/buffer-close`), the canvas and
  Close all (`:ui/buffers-close-all`); `chat-view` picks the view by the
  buffer's kind (`diff-tab-view`, `file-tab-view`, `prompt-tab-view`, else
  `text-tab-view`). Session cards get `:buffers` from the lobby's
  `:buffers {sid [{:id :kind :title}]}` (`sidebar/session-buffers`) and list
  them under the card (`session-buffer-rows`; the count toggles
  `:web/sidebar-buffers-open`); a row navigates with `:buffer-id`, which
  `router/with-buffer` turns into `:web/pending-buffer`, applied by
  `pending-buffer-tap` on `:room/joined`. The × of a row sends the roomless
  `:session/buffer-close`, so it works for parked buffers too. The palette's
  Buffers group lists the current room's, then an Other sessions group, then
  Buffer actions (`buffer-palette-groups`); `Alt+b` (`:buffers/switch` →
  `:palette/open-buffers`) opens those groups alone as a palette page.
  **Buffer presence**: `buffer-presence-tap` sends `:client/update {:buffer
  id}` whenever the active room's active buffer changes; the server keeps it
  on the client entry, `rm/client-update-presence` refreshes the room's
  `:members` (now `{:user :platform :buffer}`), and `buffers/viewers` turns
  members into `{buffer-id [user …]}` — on the room for the buffer menu, as
  `:viewers` on the lobby room summaries for the sidebar rows. Avatars via
  `sidebar/room-people`, so a single-user server shows none.
- **Code-block menu**: right-click / tap → Copy; on Read/Write/Edit blocks
  View file (`:file/open`); on blocks rendering a change View diff
  (`:ui/diff-open`, no git run). Blocks carry
  `data-file-path` / `data-diff-path` / `data-diff-text` for the delegated
  listener in `xi.web.core`; `xi.diff/tool-diff->unified` converts the tool
  diff format. A diff viewer's file header is a `ui.context-menu` trigger
  (`diff-file-menu-items`: View file via `:file/open`, Copy path); the
  trigger wrapper is the sticky element. git prints root-relative paths, so
  the file-view extension resolves against the room cwd and then its git
  root.
  The diff viewer (`diff-rows-view`) stays responsive on huge diffs three
  ways: rows / file groups / per-file body hiccup are memoized on identity
  (`diff-rows-for-text`, WeakMaps), so re-renders reuse the identical hiccup and
  Replicant skips it; line highlighting is cached per line object, not in
  `hl-cache`; and with the `:expanded` opt only `diff-row-budget` rows render
  up front, the rest behind a per-file "Show more" (`:diff/show-all`,
  `:web/diff-expanded`).
- **Rendered Markdown diffs**: a `.md` diff (tool result / ask preview via
  `tool-diff-view`, diff buffer and Git status via `diff-rows-view`'s
  `:md-code` opt) renders through `xi.markdown.diff`: per hunk, the old side
  (context + deletes) and new side (context + adds) are parsed with
  `xi.markdown.parse`, list items split into one-item blocks, and the block
  sequences LCS-diffed (`diff-segments`); `md/render-block` renders each
  block, and `mark-words` wraps the changed words of a changed run (token LCS
  over the rendered text leaves; skipped when the sides share under half the
  shorter side's words). The Rendered / Code switch toggles the diff's key
  (tool-call id, dialog id, or `[:diff filename]`) in `:web/md-diff-code`
  (`:md-diff/toggle`).
- **Permission ask focus**: an ask carrying a `:target {:arg :code :ranges}`
  (clj gate asks) renders the code in segments (`code-focus-segments`), the
  ranges at full contrast and the rest `.code-muted`; hover lifts the muting.
  An ask that is one of several for its call also carries `:block {:ranges}`
  (xi.dialog): `code-block-segments` marks those pieces `.code-block-target`,
  and a CSS `:has(.confirm-btn--block:hover)` lights up all of them while
  "Allow all" is hovered or focused.
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
  are consulted before the built-in ones. A path whose first segment is
  unknown falls back to home but keeps its URL (`pending-extension-path?`,
  `:keep-url?`): a user extension's page is re-routed once its web half has
  loaded (`xi.web.user-ext`).
- Session switching paints before the server answers. The cached history
  comes from `xi.web.cache`'s memory tier (decoded snapshots, LRU of 24,
  filled by every save, by the room being left, and by prefetch), else from
  localStorage. The first paint renders only `first-paint-window` entries; the
  timeline grows to the full window once the burst ends. Joins are paced: the
  first switch of a burst joins at once, and later ones wait until no
  navigation came for `join-burst-ms`, then join only the last
  (`:web/pending-join`; a submit flushes it, `router/flush-pending-join`).
  Once the viewed session's join lands, `xi.web.prefetch` asks the server for
  the uncached sidebar neighbours with a roomless `:session/peek`. The
  server's resume cache (`xi.session/resume-messages`) parses each
  transcript once for the peek and the join that follows.
- Unread: `:session/counts` → `:session/counts-result` (`:web/response-counts`)
  compared with the lobby's `:read` (the server's markers for *this user*, see
  architecture.md) and `:web/watched` (a localStorage overlay for an instant
  clear); viewing marks read (`:session/mark-read` + `:cache/watch`). A browser
  that connects as a different user than it cached for drops `:web/watched`
  with the rest of the cached user state (`xi.web.user-state/reset-all`).
- `visibilitychange` dispatches `:client/update {:visible? …}` so the server
  can suppress notifications while a visible client is attached.
- The tab title follows the route, most specific first: `Events · Fix the
  router · Xi` (open buffer · session), `acme-web · Xi` (a project), `All
  sessions · Xi`, `Git status · xi · Xi`; an extension page shows its page
  keyword humanized plus its route's session. `xi.web.title/page-title` is
  pure; `render!` writes `document.title` when it changes.

## Keyboard

`xi.web.keymap` resolves every keydown through the shared keymap model
(`xi.keys`, cljc): the event becomes a canonical chord (`event->chord`), the
active layers are computed from state and the DOM (transient
`:permission-pending` / `:agent-busy`, the open buffer's kind `:buffer/diff`…,
the router page `:page/chat`…, the mode, `:global`), and `xi.keys/lookup`
picks an action id, whose code `xi.web.core/install-actions!` registered.
Mode is `:compose` while a **visible** text field has focus, else
`:navigate`; focus stranded in a hidden field counts as navigate so shortcuts
are never swallowed by an invisible input, and chords that type a character
are never looked up in compose mode. The keymap itself lives in state
(`:web/keymap`): the defaults until the server's `:keys/config` (the
operator's `config.edn` `:keys`) arrives on connect. `?` opens the shortcuts
dialog (`xi.web.keymap/listing`). The user-facing tables are in the guide's
[Keyboard shortcuts](guide/keyboard.md).

## Web-only state keys

Never sent over the wire:

| Key | Contents |
| --- | --- |
| `:web/route` | `{:page … :session-id …}` |
| `:web/drafts` | compose drafts per session (`:new` before the first join, `:home` for the dashboard composer) |
| `:web/compose-images` | staged attachments |
| `:web/timeline-window` | virtualization window |
| `:web/appearance`, `:web/appearance-config`, `:web/appearance-open?` | appearance overrides, the seeded config map, dialog open |
| `:web/themes`, `:web/theme-draft` | the user's custom color themes (`xi.web.theme`, per-user state `:themes`, cached in `xi/themes`) and the editor's unsaved copy, previewed live. The active theme's CSS properties are also cached as JSON in `xi/theme-vars` for index.html's inline script to paint before main.js loads |
| `:web/lightbox` | open image src |
| `:web/watched`, `:web/response-counts` | unread tracking |
| `:web/cache` | hydrated per-session history for deep links |
| `:web/join-seq`, `:web/join-burst`, `:web/pending-join` | join pacing during a sidebar walk (`xi.web.router`): the open burst's seq and the join it deferred |
| `:web/pending-submit` | message stashed until `:room/joined` |
| `:web/pending-buffer` | `{:session-id :buffer-id}` a sidebar / palette buffer row asked to open, applied on `:room/joined` |
| `:web/session-views` | session id → the buffer (or `:chat`) this client left it on, set on navigating away and restored on `:room/joined` (`xi.web.router/restore-view`) |
| `:web/sidebar-buffers-open` | session ids whose buffer rows are unfolded; this browser's own, cached in localStorage (`xi/sidebar-buffers-open`) |
| `:web/connected?` | transport status |
| `:web/nav-items` | extension nav entries, stored at init |
| `:web/sidebar-groups` | extension sidebar groups (`:sidebar-groups`), stored at init; evaluated by `sidebar/extension-groups` |
| `:web/session-menu-items` | extension entries of every session card's context menu, stored at init |
| `:web/dashboard-hidden`, `:web/dashboard-customize?` | dashboard card ids the user switched off (per-user state `:dashboard-hidden`), and the Customize panel open |
| `:web/home-cwd` | the dashboard composer's picked project (`""`: none; unset: the most recent) |
| `:user-ext/ui` | per-extension browser-only UI state (`:bind` inputs) |
| `:user-ext/state` | per-extension slice a user extension's server half pushed to this user (`:user-ext/push`), reduced by the web half's `:handlers` |
| `:lobby` | rooms + sessions mirror (shared shape with the TUI client); its `:claude-usage` feeds the sidebar ring, its `:usage-at` tells the usage page a poll happened |
| `:web/usage` | `{:readings :history :fetched-at :loading?}` of the `/usage` page (`xi.ext.usage.web`), the server's `:usage/state` reply |

## Source files

```
src/xi/web/
  core.cljs        entry: assembly, Replicant render, auto-scroll, listeners
  views.cljs       pure views: home, chat, compose, lightbox, error cards
  router.cljs      route parsing, :route/navigate, join pacing, History API effect
  dashboard.cljs   home dashboard: card registry, visible-cards, grid + card frame
  title.cljs       document.title from the route (pure)
  cache.cljs       offline cache: memory tier + localStorage (hydrate + persist tap)
  prefetch.cljs    :session/peek of the viewed session's sidebar neighbours
  appearance.cljs  appearance settings layering
  theme.cljs       custom color themes: OKLCH scales → CSS properties, the user's value (pure)
  keymap.cljs      view- and mode-scoped shortcuts
  viewer_group.cljs super-collapsed summaries (pure)
  user_ext.cljs, user_ext/  browser halves of user extensions (lazy :user-ext module)
  demo.cljs        fabricated data for the static ?demo=<view> render
src/xi/ext/usage/web.cljs         the /usage page (cards, pace notes, charts) + its dashboard card
src/xi/ext/home/web.cljs          the dashboard's Active / Recent chats / Projects cards
src/xi/usage.cljs                 readings, provider parsers, history, pace maths (pure)
src/xi/client/ws_transport.cljs   shared WS transport (forward+mirror, reconnect)
src/xi/server/ws.cljs             WS server + static file serving
src/xi/server/usage.cljs          usage sources, polling, history file
resources/public/                 index.html, css/style.css, theme.css, ui-runtime.js
```
