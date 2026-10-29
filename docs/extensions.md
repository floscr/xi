# Extensions

Xi extensions add behaviour via data maps composed at assembly time. There
are no registration atoms or global state — extensions are plain
ClojureScript maps wired through `xi.cli` into the core event loop,
provider effects, and TUI.

> Writing a new **built-in** extension? Follow the step-by-step recipe in
> [writing-extensions.md](writing-extensions.md) — this file is the
> reference for every key.
>
> Want one without rebuilding xi? Put it into `~/.config/xi/extensions/` and
> list it under `:extensions` in `~/.config/xi/config.edn`. These **user
> extensions** load at runtime in a capability sandbox (`xi.ext.user`,
> `xi.ext.user.guard`, `xi.sandbox.sci`, `xi.api.*`) with a subset of the
> keys below; they are documented for users in the guide
> ([extensions](guide/extensions.md), [reference](guide/extensions-reference.md))
> and the tutorial example is exercised end to end by
> `test/xi/ext/user_test.cljs`. Their roomless channel to browsers: an own
> event dispatched with `:to-users` / `:to-client` is sent by `xi.server.ws`
> as `:user-ext/push` to those devices, where the web half's `:handlers`
> reduce it into `[:user-ext/state <id>]` (`xi.web.user-ext`).

## Extension Shape

```clojure
(def extension
  {:id               :my-ext           ; keyword (required)
   :init             {:room    {...}    ; template merged into each room's [:ext :my-ext]
                      :process {...}}   ; installed at top-level [:ext :my-ext]
   :persist-room     true | [key …] | fn ; room state kept across restarts (see below)
   :handlers         {event-type handler-fn}  ; chained AFTER base handlers
   :fx               {fx-type (fn [ctx payload])}
   :event-hooks      {event-type (fn [event state] → event'|nil)}
   :tool-definitions [{:name :description :input_schema}]  ; :resolve (fn [] → keys), merged in per turn
   :tool-registry    {name (fn [args ctx] → result|Promise)} ; ctx: {:cwd :client-pid :dispatch! :get-state :room-id :confirm!}
   :remove-tools     #{tool-name}      ; builtin tools to hide from the model
   :commands         [{:name :description :handler   ; see xi.commands for the listing keys:
                       :aliases :subcommands :takes-args? :while-busy? :surfaces}]
   :system-prompt    str | (fn [cwd] → str|nil)
   :keybindings      [{:key "alt+r" :event {...} :when (fn [state])
                       :id :my-ext/do :label "Do it" :layer :global}]
   :prompt-badge     (fn [state] → str|nil)
   :on-shutdown      (fn [])
   :on-enable        (fn [])           ; runtime enable hook (see "Runtime enable/disable")
   :on-disable       (fn [])           ; runtime disable hook; owns its own teardown
   ;; WS server routing (server surface)
   :server-fx        (fn [{:keys [send!]}] → {fx-type (fn [ctx payload])}) ; reply to one client
   :roomless-events  #{event-type}     ; may be sent without joining a room
   :no-broadcast     #{event-type}     ; never echoed to the room's clients
   :originator-only  #{event-type}     ; sent only to the originating client
   :lobby-relevant   #{event-type}     ; push fresh :lobby/state afterwards
   :usage-sources    [{:id kw :fetch (fn [ctx] → Promise<[reading …]>)}] ; /usage page accounts (see "Usage Sources")
   ;; web-client surface (browser build only — see "Web Client Surface")
   :routes           {"seg" {:parse fn :path {page-kw fn} :roomless-pages #{page-kw}}}
   :pages            {page-kw (fn [state dispatch!] → hiccup)}
   :nav-items        [{:menu :sidebar|:palette|:home-topbar|:overflow ...}]
   :sidebar-groups   [{:id kw :label str :where session-key :limit n :more {...}}]
   :session-menu-items [{:label str :label-on str :flag session-key :icon kw :event {...}}]
   :taps             [(fn [dispatch!] → tap-fn)]
   :block-menu-items (fn [block state] → [{:label :icon :event}])  ; code-block menu (xi.web.block-menu)
   :terminal-toolbar-items (fn [buffer state] → [{:label :icon :title :send|:event}])  ; xi.web.terminal
   :buffer-views     {kind {:view (fn [state dispatch! room id buffer] → hiccup) :icon kw}}  ; xi.web.buffer-views
   :timeline-posts   (fn [state dispatch! room entries sub-id] → [{:pos :key :node}])  ; xi.web.timeline-posts
   :overlays         {kw (fn [state dispatch!] → hiccup|nil)}})  ; xi.web.overlays, over every page
```

## Architecture

Which extensions load is declared in **`src/xi/config.cljc`** — plain
Clojure, one vector per surface (`server`, `client`, `web`):

```clojure
(def server
  [plan-mode/extension    ; extension map → used as-is
   chrome/create          ; factory fn → called with ctx by ext/instantiate
   …])
```

The one file is shared by every build via **custom reader features**
(shadow-cljs `:compiler-options {:reader-features …}`): the node builds
(`:main`, `:test`) read it with `#?(:node …)`, the browser build with
`#?(:browser …)` — so each target only requires + compiles the extensions
for its surface, and node-only code never leaks into the browser bundle.

Order matters — `ext/compose` chains handlers/gates in list order. The
plan is to eventually load these entries at runtime via SCI; until then
the config is compiled in like any other namespace.

```
xi.config.cljc ── #?(:node server / client)   #?(:browser web)

xi.cli (assembly)
├── server-extensions  →  ext/instantiate → ext/compose → composed map
│     (xi.config/server — factories get   │
│      ctx {:ring … :ask! …})            │  merge into
├── client-extensions  →  ext/instantiate │  app handlers,
│     (xi.config/client)                  │  fx, commands,
└── create-app ←───────────────────────┘  tools, etc.
      ↕ events      ↕ effects
    handlers        fx handlers

xi.web.core ── web-extensions (xi.config/web) → ext/instantiate → ext/compose
```

### Per-mode assembly

| Mode | Extensions |
|------|-----------|
| **Standalone** | server + client composed together |
| **Server** | server extensions only; state mirrors to clients |
| **Client** | client extensions local; server ext state mirrored |

## State Scoping

Extension state lives in two places:

- **Room-scoped** `[:rooms rid :ext <id>]` — rides in the `:room/joined`
  snapshot, mirrors to clients. Used for: plan-mode `:enabled?` and yolo `:level`.
- **Process-local** `[:ext <id>]` — never crosses the wire. Used for:
  the rules engine's server-session rules.

Declare initial state via `:init`:

```clojure
{:init {:room    {:enabled? false}   ; per-room, mirrored
        :process {:rules []}}}       ; per-process, local
```

### Surviving restarts: `:persist-room`

Room state lives in memory, so a server restart (`bb serve:restart`) or a
reaped room starts the chat from `:init` again. An extension keeps part of it by
declaring `:persist-room` — `true` for its whole room slice, a vector of the
keys to keep, or a fn from the slice to the map to keep (applied again to the
saved slice on the way back in, so it must be idempotent; the sub-agents
extension saves its children without their histories this way). `xi.ext.persist` (server and standalone only) saves those slices,
as plain EDN keyed by extension id, to `<session-id>.ext.edn` beside the chat's
metadata whenever they change, and on `:session/resumed` reads them back with
`:ext.persist/hydrate` (a handler `xi.ext.core/compose` adds, so a client mirror
replays it). Blank values aren't saved. On the way back in, a key the room holds
nothing for takes the saved value; two sequences are joined (current entries
first, no duplicates; maps with an `:id` count as duplicates by that id); any
other value in the room wins. The rules engine uses
it so an Always answer lasts for the chat, not for the server process.

## Handler Contract

Handlers are pure: `(fn [state event]) → {:state :effects} | nil`.

Effects are data `[[:fx/type payload] …]`. Effect handlers receive
`{:dispatch! :get-state …}` — they perform side effects and may dispatch
new events.

Events a connected client sent carry `:client-id` and `:user` (the sender's
user id, stamped by `xi.server.ws`; see architecture.md "Users"). Use
`state/event-user` to resolve the acting user for any event, including ones
with no client (standalone input, server automation); it falls back to the
only user in the event's room, then to the process' own user. Presence is
`[:rooms rid :members]`, kept current by `:room/presence`. User records
(`[:users id]`, with the data extensions keep per user) and `xi.api.user`:
see architecture.md "Users".

Extension handlers chain AFTER the base handler for each event type:

```clojure
;; Chained onto :agent/turn-end (base handler has already updated state)
(defn- on-turn-end [st {:keys [room-id aborted?]}]
  (when (and (not aborted?) (enabled? st room-id))
    {:effects [[:notify/desktop {:title "Turn complete"}]]}))
```

## Tool-Gate

A 2-arg function that intercepts tool execution:

```clojure
(fn [tool-call ctx] → tool-call | nil | {:intercepted true :result …})
```

- Return `tool-call` to allow.
- Return `nil` to block (surfaces "Blocked by Xi permission gate").
- Return `{:intercepted true :result {:content [...] :is-error bool}}`
  to short-circuit with a custom result.
- May return a Promise for async operations (e.g., confirmation dialogs).

The gate ctx: `{:dispatch! :get-state :room-id :cwd :confirm!}`.
`:confirm!` is `(fn [message] → Promise<bool>)` — raises a confirm dialog;
resolves to `false` (safe default) at once only in prompt mode, where no
client can ever attach; otherwise it waits for an answer. A second
arg `{:options [:yes :no :always …]}` adds extra choices as data — option
keywords from `xi.dialog/confirm-option` (e.g. `:always` resolves
`:always`, `:allow-repo` resolves `:repo`); the TUI and web render the
dialog generically from that vector, so no template changes are needed.

**Asks as a block.** Each tool call's ctx is scoped by
`xi.dialog/scope-confirm-to-call`, which also adds `:expect-asks!`
`(fn [targets])`. A tool that will ask several times for one call (the clj
gate) declares up front the `:target` of each ask it may raise. While more
than one is outstanding, every ask gets the `:allow-block` option (resolves
`:block`) and a `:block {:count :arg :ranges}` with the union of the
outstanding asks' ranges, which the web highlights while the button is
hovered. Answering it resolves that ask to `true`, and the call's later
*declared* asks (matched by `:target`) to `true` without a dialog. Undeclared
asks (a runtime path gate) still open a dialog. Tools that ask once need
nothing.

Tool exec-fns get the same context plus the provider's own keys:
`{:cwd :client-pid :dispatch! :get-state :room-id :confirm!}` (in a
sub-agent turn, `:room-id` is the parent room and `:confirm!` asks there with
the dialog tagged `:sub-id`, `xi.subagent/confirm-fn`; explanations and
questions deny instead).
So a tool can mutate state by dispatching events itself — implement tools in
`:tool-registry`, never inside a gate. Policy (whether a call may run at all)
belongs in rules: add a default rule to `xi.rules.defaults`, matching
extension tools by `:tool-name` (see the guide's [rules reference](guide/rules-reference.md)).

A `:tool-registry` entry named like a builtin (`read`, `bash`, …) replaces
the builtin's implementation for every provider (the treesitter `read`
outline works this way); rules still see the call under its builtin name.

## Dialogs

Dialogs are **data**, not templates. `ext/create-dialogs` returns `ask!`:

```clojure
(ask! {:dispatch! … :state …} {:room-id … :dialog {…}}) → Promise<answer>
```

It pushes the dialog map into the room's `[:ui :dialogs]`; the TUI and web
render it generically from the data, and the promise resolves with the
user's answer. It waits for one even while no client is connected (the
dialog shows when a client joins); only prompt mode (`:clientless?` in the
connection state) resolves to a safe default — `false`/`nil` — at once.
Dialog types:

- `{:type :confirm :message … :options [:yes :no :always …]}` — option
  keywords come from `xi.dialog/confirm-option` (label, key hint, resolved
  value); resolves to that option's value (`true`/`false`/`:always`/…).
  Omitting `:options` defaults to `[:yes :no]`.
- `{:type :form :message … :fields [{:name "topic"} …]}` — a multi-field
  text form (Enter/Tab moves between fields in the TUI; textareas on web).
  Field labels default to a humanized `:name`
  (`xi.dialog/form-fields`). Resolves to a map of field name → entered
  string, or `nil` on cancel. Used by the skills extension to collect
  `<input />` placeholder values for `/skill load`.

Adding a new option keyword or dialog field requires no renderer changes —
both clients build the UI from the dialog map.

**Deny with a reason.** A gate that wants the user's reason for a deny wraps
its `confirm!` with `xi.dialog/capture-deny-reason`, which passes
`:on-reason` to `ask!`. `ask!` marks the dialog `:deny-reason? true` (the
clients' cue to offer the option: the web `⋯` beside Deny, `/deny <reason>`
in both clients) and keeps the callback out of the mirrored dialog data. A
`:ui/dialog-response` with `:value false :reason "…"` calls the callback
before the promise resolves, and the answer stays a plain `false`, so boolean
callers are unaffected. The gate builds the tool result with
`xi.dialog/with-deny-reason` ("The user denied this tool call. To tell you how
to proceed, the user said: …"; the clj gate keeps its own first line). Asks
without `:on-reason` don't offer the option; a reason sent anyway is dropped.

## Event Hooks

Pre-dispatch transforms: `(fn [event state] → event' | nil)`. Returning
`nil` blocks the event. Never run on `:remote?` (mirrored) events.

```clojure
;; clipboard-image: transform input text, attach decoded images
{:event-hooks {:input/submit transform-input}}
```

## Commands

Pure handlers: `(fn [state {:keys [room-id args commands]}]) → {:state :effects} | nil`.

Defer I/O to effects:

```clojure
(defn- commit-command [_st {:keys [room-id args]}]
  {:effects [[:commit/start {:room-id room-id :args args}]]})
```

## System Prompt

Static string or a function of `cwd`:

```clojure
;; Conditional on project markers
:system-prompt (fn [cwd]
                 (when (marker-files-present? cwd)
                   "# Clojure tools available..."))
```

## Keybindings

Declarative keyboard actions (`xi.keys`):

```clojure
:keybindings [{:key   "alt+r"                   ; default key, user-rebindable
               :event {:type :ext.notes/toggle}
               :id    :notes/toggle             ; optional, default: the event :type
               :label "Toggle notes"            ; optional, shown in the shortcut list
               :layer :global                   ; optional: :buffer/diff, :mode/navigate, …
               :when  (fn [state] ...)}]        ; optional guard
```

Each entry is an action: the TUI (`xi.client.tui`) merges them with its
built-in actions, their `:key`s become a defaults layer between Xi's defaults
and the user's `config.edn` `:keys` (so a user can rebind or unbind them by
action id), and `xi.tui.keys/decode` turns any terminal key into the
canonical chord — no per-key table to extend. A guarded binding falls through
to the next layer / the editor's own handler when the guard fails. Events are
dispatched with `:room-id` added automatically. The shared model (layers,
notation, resolver, defaults) is `src/xi/keys.cljc`; the user-facing tables
are in the guide's [Keyboard shortcuts](guide/keyboard.md).

## Prompt Badges

```clojure
:prompt-badge (fn [state]
                (when (enabled? state) " 🔔"))
```

Rendered after `xi>` on every draw.

## Factory Extensions

A config entry is either an extension **map** (used as-is) or a **factory
fn** `(fn [ctx] → ext|nil)` — `ext/instantiate` calls factories with a
per-surface ctx map (`server` gets `{:ring … :ask! …}`; `client`/`web`
get `{}`). Factories close over runtime resources and may return nil when
unconfigured:

```clojure
;; mcp: nil without a :manager in ctx (the client mirror) → ext/compose drops it
(defn create [{:keys [manager]}]
  (when manager
    {:id :mcp ...}))

;; events: closes over the ring buffer from ctx
(defn create [{:keys [ring]}]
  {:id :events ...})
```

The `server` ctx also includes `:manager` (the live extension registry). A
factory that wants to toggle extensions at runtime — e.g. the `/ext` and
`/mcp` control commands — reads it and returns nil where there is no manager
(so the client mirror gets nothing).

## Runtime enable/disable

Assembly freezes one `ext/compose` snapshot per process. `xi.ext.manager`
keeps that snapshot **live** so extensions can be enabled/disabled
mid-session (`/ext list|enable|disable`), firing the `:on-enable` /
`:on-disable` hooks. The provider re-reads the composed tool set on every
turn (via the fn-valued tooling seam in `xi.cli/tooling-opts`, deref'd in
`xi.tools.registry/resolve-tooling`), so tool changes take effect on the
next turn without a restart.

**Scope:** only *use-time* surfaces hot-swap — `:tool-definitions`,
`:tool-registry`. Construction-time surfaces (`:handlers`,
`:event-hooks`, commands, `:keybindings`, `:system-prompt`, `:taps`,
`:routes`) are baked at assembly and need a restart to fully change. Design
runtime-toggleable extensions to contribute only tools.

**External MCP servers** are built on this: `xi.ext.mcp` wraps each
configured MCP server (`~/.config/xi/mcp.edn`) as an extension contributing
`mcp__<id>__*` tools and registers it into the manager. See
[mcp-internals.md](mcp-internals.md).

## Usage Sources

`xi.server.usage` polls every subscription the server can read (the Claude
login, the Codex login, an Ollama Cloud key, an OpenCode Go key) and the
composed `:usage-sources`, at start, every five minutes, on a client's
`:usage/refresh` (throttled) and when the Claude credentials file changes.
A source is `{:id kw :fetch (fn [ctx] → Promise<[reading …]|nil>)}`; a
reading is the card shape `xi.usage` documents (windows with a used percent,
reset time and period; balances; badges; notes). A nil or failing fetch keeps
the source's last readings. The readings stay in memory; the per-window
sample history behind the page's charts (`xi.usage/record`, two weeks) is
written to `~/.config/xi/state/usage-history.edn`.

The lobby broadcast carries the live Claude reading's summary as
`:claude-usage` (the sidebar ring) and the last poll time as `:usage-at`; the
roomless `:usage/fetch` is answered with `:usage/state {:readings :history
:fetched-at}` for the `/usage` page (`xi.ext.usage.web`, a built-in web
half). User extensions may declare `:usage-sources` too; the guard hands
their `:fetch` the same capability ctx as a lifecycle hook.

## Web Client Surface

Extensions can contribute routes, pages, navigation entries and taps to the
browser client. Because the web client is a separate shadow-cljs `:browser`
build, an extension with a web UI is split in two:

- `src/xi/ext/canvas_review.cljs` — the node/server half (tools, server
  handlers, state), listed in `xi.config/server`.
- `src/xi/ext/canvas_review/web.cljs` — the browser half (its buffer view,
  client handlers), listed in `xi.config/web`. It may require
  `xi.web.views` (shared building blocks: `menu-button`, `overflow-menu`,
  `spinner`, `shorten-path`, `diff-rows-view`) and `ui.*` components, but
  core web namespaces never require extension code.

The two halves share an `:id` and talk over the same WS events (the room's
mirrored `[:ext <id>]` state plus any request/reply pair they define).

### Keys

- **`:routes`** — `{"first-url-segment" entry}`. `(:parse entry)` receives
  the remaining path segments and returns a route map (`{:page kw …}`);
  `(:path entry)` maps `page-kw → (fn [route] → url-path)` for
  `route->path`; `:roomless-pages` is a set of pages that imply leaving the
  active room on navigation (unioned with the base `#{:home}`). The router
  rebuilds the route from a fixed key set; a `:params` map is the one key it
  keeps verbatim, so an extension page's own ids (`/messages/<conv>`) ride
  there and come back to its `:path` fn and page.
- **`:pages`** — `{page-kw (fn [state dispatch!] → hiccup)}`; `root-view`
  consults this table before its built-in cases.
- **`:nav-items`** — data-only entries `{:menu … :label … :icon … :event …}`
  rendered by core views; stored in app state at `:web/nav-items` during
  init. Menus: `:sidebar`, `:palette` (Cmd+K), `:home-topbar`, `:overflow`.
  Overflow items may carry `:mode` (`:project`, `:room`, `:dashboard`, …) to show only in
  a matching topbar context; the context's `:cwd`/`:room-id` are merged into
  the `:event` on click. A sidebar item may carry `:badge-path`, a state path
  whose positive number is drawn as a badge (`views/nav-badge`; the sidebar
  memo key includes it).
- **`:sidebar-groups`** — data-only groups of the drawer sidebar, shown
  between Drafts and Recent: `{:id kw :label str :where session-key
  :limit n :more {:label :icon :event}}`. A group lists the lobby's sessions
  whose `:where` key is truthy (a per-user flag the server puts on each
  session, such as `:favorite?` from an extension's `:session-flags`), most
  recently visited first, at most
  `:limit` of them; `:more` is a closing row that appears once there are more
  sessions than the limit. Empty groups are not drawn. The sessions also stay
  in their Recent / Earlier group, so ALT+j/k skips the extension groups. The
  group collapses like the core ones (its `:id` is the collapse key).
- **`:session-menu-items`** — data-only entries of every session card's
  context menu (right-click, long-press, the ⋮ button), in their own section
  between the session actions (pin, rename, …) and the copy actions:
  `{:label str :label-on str :flag session-key :icon kw :event {…}}`.
  `:event` is dispatched with the card's `:session-id` merged in; `:label-on`
  replaces `:label` while the card's `:flag` key is truthy (Add to favorites →
  Remove from favorites). Both are also open to
  user extensions (their web half; the guard confines the events, see
  [extensions-reference.md](guide/extensions-reference.md#browser-halves)).
- **`:dashboard-cards`** — cards of the home dashboard (`/`,
  `xi.web.dashboard`, whose ns doc has the full map): `{:id :<ext>/name
  :title :icon :description :order :size :when :load :render :more}`. Core
  draws the frame (title, icon, `:more` link, a per-card error box) and calls
  `(:render card) state dispatch!` for the body; `:load` is dispatched on
  entering the dashboard (and on a reconnect while on it), `:when` hides the
  card, and the user's Customize panel hides it by `:id`. The built-in cards
  are `xi.ext.home.web` and `xi.ext.usage.web`'s Usage card.
- **`:taps`** — `(fn [dispatch!] → (fn [event state]))` factories, added via
  `add-tap!` at init (e.g. fire a stashed action once `:room/joined` arrives).
- **`:block-menu-items`** — `(fn [block state] → items)`, asked when the
  code-block menu opens (`xi.web.views/code-copy-menu`); `block` is `{:text
  :path :line :lang :diff-path}`. File headers (`xi.web.views/file-header`)
  ask it with `{:path}` on each render; an item with `:header? true` is also
  a button in the header (`file-header-actions`). Registered in `xi.web.block-menu` (fns, so outside
  the state, like `xi.web.palette-items`).
- **`:terminal-toolbar-items`** — `(fn [buffer state] → items)`, the floating
  buttons of a terminal buffer; an item `:send`s text into the terminal or
  dispatches its `:event`. Registered in `xi.web.terminal`.
- **`:buffer-views`** — `{kind {:view (fn [state dispatch! room id buffer]
  → hiccup) :icon kw}}`, the body of a room buffer of that kind (`xi.buffers`)
  below the chat topbar, for kinds the chat view has no built-in view for;
  `:icon` marks the kind in the view switch and the sidebar. Registered in
  `xi.web.buffer-views`; built-in web halves only. Example: the review canvas
  (`:canvas`, `xi.ext.canvas-review.web`), whose buffer the shared
  `:canvas-review/load` / `:canvas-review/hydrate` reducers install.
- **`:timeline-posts`** — `(fn [state dispatch! room entries sub-id] → [{:pos p
  :key str :node hiccup}])`, posts placed in the chat timeline before history
  entry `p` (`p` = the history length: at the end), outside any folded tool
  group; a `p` above the rendered window pins at its top. A sub-agent's view
  asks with its `sub-id` and its history as `entries`, the chat with nil.
  Keys are unique per room. Registered in `xi.web.timeline-posts`; built-in web halves only.
  Example: the agent blocks (`xi.ext.blocks.web`), each after its tool call.

- **`:overlays`** — `{kw (fn [state dispatch!] → hiccup | nil)}`, views the
  root view draws over every page, after the dialogs; nil draws nothing. For
  state that outlives a page (the device extension's floating camera
  thumbnail while the phone shares its camera). A fixed overlay that must
  clear the composer uses `--compose-dock-height` (set on the root by
  `xi.web.core`, 0 off the chat). Registered in `xi.web.overlays`; built-in
  web halves only.

`:block-menu-items` and `:terminal-toolbar-items` are open to user web halves too (`xi.web.user-ext.guard/wrap` confines
their events like `:palette-items`).

### Routing hooks

There is no on-enter callback: extensions chain a `:route/navigate`
handler after the base router handler (`ext/merge-handlers`), syncing
drill-down state from the route and emitting fetch effects:

```clojure
(defn- on-navigate [st {:keys [page cwd]}]
  (when (= page :my-list)
    {:state   (assoc st :web/my-cwd cwd)
     :effects (when (empty? (:web/my-items st))
                [[:app/dispatch {:type :my-ext/load :cwd cwd}]])}))
```

Example: `xi.ext.usage.web` (the /usage page).

## Writing a New Extension

Follow [writing-extensions.md](writing-extensions.md) — the step-by-step
recipe, contracts, register step, and a table of example extensions to copy.

## Built-in Extensions

Which of these load, and in what order, is `src/xi/config.cljc`; each
namespace docstring is the authoritative description.

**Policy & safety**

| Extension | What it does |
|-----------|--------------|
| rules | Declarative rules engine — every allow/deny/confirm policy; `/rules`. Loaded first. User docs: [rules](guide/rules.md), [reference](guide/rules-reference.md); the engine's request shape and `decide!` live in `xi.ext.rules` / `xi.rules.store`. |
| plan-mode | Read-only exploration mode (`/plan`, 📋 badge). The read-only policy itself is a default rule. |
| yolo | Skip confirmations for a room at a level, `:sane` or `:relentless` (`/yolo [sane\|relentless\|off]`, 🔥 / 🔥🔥 badge; has a web half that mirrors the level and shows a header chip). `/yolo` dispatches `:ext.yolo/set {:level}`, the echoed event every client reduces. The level is `:persist-room` (off removes the key), so a reaped or restarted room gets it back. The skipping is in `xi.ext.core/create-dialogs`: a `:confirm` the level answers resolves yes without opening (`xi.dialog/auto-confirm?`), so rule asks, the clj gate's prompts and sub-agent spawns all run; setting a level dispatches a yes `:ui/dialog-response` for each such dialog already open. Which level answers an ask is its `:guard`: the rules engine copies a rule action's `:guard` (`:sane`, `:always`; `xi.rules/guard`) onto the dialog, and the clj gate tags its own guarded confirms (out-of-project rm dirs, `yolo-guarded-patterns`). `:sane` answers unguarded asks only, `:relentless` all but `:always`. Hardened-tier asks are tagged `:hardened?` and always open; denies and nudges are untouched. What sane yolo refuses outright is the `xi.rules.defaults/yolo-sane` bundle (`:when {:yolo {:level :sane}}`). |

**Agent tools**

| Extension | What it does |
|-----------|--------------|
| clj | Sandboxed Clojure (SCI) scripting tool + `run_task` tool (bb / npm / pnpm / yarn / deno tasks) — replaces bash. User docs: [clj tool reference](guide/clj-tool-reference.md); worker and gate internals in [architecture.md](architecture.md#the-clj-tools-worker). |
| process-manager | Registry of background processes started via clj's `process` ns; `/ps`, `/kill`. Web half: the chat header's process chip and live `:process-log` buffers (`proc:<pid>`). |
| treesitter | Large-file `read` → structural outline; `read_source`. See [treesitter.md](treesitter.md). |
| clj-surgeon | Structural Clojure refactoring tools; auto-fixes parens after write/edit. |
| commit | Hunk-level staging + commit tools; `/commit`. |
| session-search | Search previous sessions by title and content. |
| events | Agent tool for inspecting the session event log. |
| subagent | Background sub-agents (`spawn_subagent` …); `/subagents`. Every `:subagent/spawn` gets the parent conversation in its system prompt (`xi.ext.subagent.handlers/child-system`: a history excerpt); `:parent-context? false` opts out. Sub-agent turns, and only those, also get the `parent_history` tool (`child-tool-definitions` / `child-tool-registry`, added by `xi.cli/subagent-opts`; pages from `handlers/history-page`), which reads the parent room's live `:history` through the tool ctx's parent `:room-id`, so it works for every provider; a profile's `:only-tools` allowlist gets it added at spawn. The web queue popover's ▶ sends `:subagent/run-queued` (forwarded), which drops the queued prompt and spawns it with its images. The web client's Explain button on a permission-gated tool block sends `:subagent/explain-call` (web half forwards it), which spawns an ordinary sub-agent with id `explain-<call-id>` (`xi.ext.subagent.handlers/explain-call`, prompt from the call + ask + recent history). The tool block finds its explanation by that id in the mirrored `:agents`; the timeline's sub-agent posts hide those (`inline-sub?`). Every child records `:anchor` (the room history's length at spawn) and, at turn end, `:ended-anchor`; `xi.web.views` posts the children spawned at one anchor as one row before that history index (`subagent-row`, or `subagent-batch-row` for several; the `spawn_subagent` call that started a child hides behind it), with its state, a one-line summary and whether it waits on a permission ask (`xi.web.subagents`); anchors above the rendered window pin at its top. A row opens the child in the right sidebar (`subagent-view`: its rows come from `subagent-items`, which applies the chat's block rules — `xi.web.appearance/block-collapsed?`, `:tool-args`, the session's `:web/block-open` under `sa-<sub-id>-<i>` — folded with `group-viewer-items`; a child without history, as a reloaded chat keeps it, shows its `:result`), a batch row their list (`subagent-group-view`, layout view `sa-group:<first-id>`). The composer's Agents button (`agents-button`) lists every child under the prompt before its anchor (`xi.web.subagents/by-turn`). A child's permission asks (dialogs tagged `:sub-id`) never post in the chat: `dialog-post` skips them, their resolved entries carry `:sub-id`, and its view answers them (`subagent-asks`). The diff viewer's selection composer (`xi.web.views/diff-composer`, attached under the selected lines; its Ask / Explain / Modify switch is the view's `:intent` in `xi.web.diff-state`, sent by `:diff/compose-submit`) starts a thread via `xi.web.core/diff-thread-start`, which sends a plain `:subagent/spawn` with an `ask-…` id (`ask-sub-id`; a Modify gets `modify-…`, `modify-sub-id`, inline but not `:unattended?`, so it may edit with permission asks) and a prompt holding the text and the selected diff snippet (`diff-thread-prompt` by the thread's `:kind` `:ask` / `:explain` / `:modify`). Each thread is a client-local thread in `:web/diff-asks` (its diff `:view`, snippet, anchor `xi.diff/line-key` of its last line, turns of question + sub-id) rendered inline after that line; follow-ups spawn one more sub-agent with the answered turns in the prompt, and Close aborts or dismisses the thread's children. The timeline posts hide them the same way. The web sidebar lists a room's sub-agents with its buffers: `xi.server.room-manager/session-buffers` appends `{:kind :subagent :status}` rows to the lobby's `:buffers` (the ext marks spawn/turn-end/dismiss/promoted `:lobby-relevant`); a row navigates with `:buffer-id` = the sub-id, which `xi.web.router/buffer-open-event` turns into a client-local `:ui/buffer-switch` to that id (it names no buffer; `xi.web.views/subagent-view` renders the child's history in place of the chat, its URL segment is `subagent:<id>`, and dismissing the child in view falls back to the chat); its button sends the roomless `:session/subagent-stop` / `:session/subagent-dismiss` (by session id, like `:session/buffer-close`), which the node half re-dispatches into the session's live room as `:subagent/abort` / `:subagent/dismiss`. **Saved as sessions.** The node half chains `:subagent/save!` onto `:subagent/turn-end` (`save-fx`): the transcript moves out of the throwaway config dir into a session tagged `:subagent-origin {:session-id <parent> :sub-id}` (`xi.session/promote-subagent-session!`; listings skip it, `delete-session!` deletes it with its parent via `subagent-sessions`), and `:subagent/promoted` puts its `:session-id` on the child. The agents list is `:persist-room` (`handlers/persisted`: each child without `:history`, a running one saved `:stopped`), so the child entry is the parent's link block and comes back on resume; `:session/resumed` / `:session/created` first drop the previous conversation's finished children (`reset-for-session`), and legacy `:promoted-subagents` still reseed stubs. `:subagent/upgrade` (a child) and the roomless `:session/subagent-upgrade` (the child session's own banner) run `:subagent/upgrade!`, which drops the marker on disk (`upgrade-subagent-session!`), dispatches `:subagent/upgraded` into the parent's live room, clears the marker in the child's live room (`:session/updated`) and refreshes the lobby (`:subagent/session-upgraded`). **Replies.** `:subagent/reply {:sub-id :text}` (`handlers/reply`, forwarded by the web half and applied on its echo) runs another turn on a finished child: its history gets the `:user` entry, `:exchanges` keeps every answer, and the `:subagent/start` effect carries `:resume` (the child's `:cli-session-id` / `:session-id`) plus `:history` (task + history, for sessionless providers). `xi.subagent/resume-target` resumes in the kept throwaway dir when the child isn't saved yet, else in the live config dir, so the reply appends to the hidden session's transcript; a failed resume reports a `:subagent/error`. `final-text` reads only the entries after the last `:user`. Tool ctxs carry the running `:call` (`xi.tools.registry/run-tool`), so `spawn_subagent` records its call id as the child's `:call-id`; the timeline anchors cards after that call (`xi.ext.subagent.threads/anchor-of`), which survives a history rebuilt from the transcript (no thinking entries). **Threads.** `xi.ext.subagent.threads`: a node is `{:index :kind :call-id :hint}` of the room history, the same with `:sub-id` for a child's history, or `{:sub-id}` for a child (`locate` finds it again after a rebuild; `address` prints `#12` / `sa-x#4` / `sa-x`). `:subagent/ask {:node :question}` (or `{:thread :question}` for a follow-up, forwarded by the web half) resolves the node's text server-side and spawns an `ask-` child with `:node :thread :question` and the earlier answered turns in its prompt; An ask may carry `:selection` (text the user selected in the block: quoted in the prompt, kept on the child and shown in the thread card) and the asking `:client-id`. `:subagent/thread-close` marks the thread's children `:closed?` and stops running ones. `threads` groups open threads; `xi.web.views` posts a room node's threads after its entry and a child's in its view (`subagent-view`), `xi.web.threads` holds the composer (`:web/thread-compose`) and the touch menu (`:web/thread-menu`) and moves the one `.ask-hover` button to the hovered `[data-ask-index]` / `[data-ask-sub]` post (`views/with-ask-ref`). `parent_history` takes `agent` and `subagent_result` takes `history` to read a child's history, from memory or rebuilt from its saved session. Dismissing without a `:sub-id` keeps inline children (threads, explanations). |
| mcp | Wraps external MCP servers (`~/.config/xi/mcp.edn`) as extensions; `/mcp`. See [mcp-internals.md](mcp-internals.md). |
| extensions | `/ext list\|enable\|disable\|reload` over the live extension manager (`reload` re-reads [user extensions](guide/extensions.md); agents get the same as the `ext_reload` tool). |

**Sessions, review & workflow**

| Extension | What it does |
|-----------|--------------|
| resume | `/trim`, `/rollover`, `/lineage`. See [architecture.md](architecture.md#session-tools) and the guide's [sessions](guide/sessions.md). |
| worktree | `/worktree` — move the room into a fresh git worktree (`merge`/`list`/`remove`). |
| blocks | Agent blocks (`ui_block` / `ui_update` / `ui_state`; user guide [agent-blocks.md](guide/agent-blocks.md)). `xi.blocks` (cljc, pure) is the kind catalog: normalizing a model payload into a spec, caps, `act` (which actions a kind takes; a block action only ever changes its own block), text forms; `xi.blocks.hiccup` is the `hiccup` kind's allowlist. The tools build the record and dispatch `:ext.blocks/add|update|read` into the room slice `[:ext :blocks :blocks]` (`:persist-room`); the shared reducers (`xi.ext.blocks.handlers`) refuse add/update/remove/read events that carry a `:client-id`, so only `:ext.blocks/act` comes from clients. The record's `:call-id` is its own running tool call (matched by name + arguments like a permission ask), its `:anchor` the history length; a sub-agent's tools act in the parent room, so its block carries `:sub-id` and both refer to that sub-agent's history (its view draws it, the chat doesn't, and it goes with the sub-agent in `xi.blocks/cut-to`); each change stamps the block's text on that call's history entry as `:block-text` (the TUI shows it), and prompt mode prints it from its tap. The web half draws blocks through `:timeline-posts` after their call; submit / send actions also send `:input/submit` with `xi.blocks/action-message`. User extensions add kinds with `:block-kinds`: `xi.ext.user.guard` names them `<id>/<name>`, `ext/compose` collects them, and `xi.ext.blocks/create` reads them from the manager, so `ui_block`'s def lists them through its `:resolve`; their records carry `:rules` (the actions they take) and `:text-form`, so reducers and clients need no registry. A web half's renderers live in `xi.web.block-kinds` and are drawn through the hiccup allowlist. `xi.api.blocks` posts and updates blocks from extension code (`xi.api.core/set-block-kinds-host!`, set by the loader). Tools with a UI (MCP Apps) show as blocks of the internal kind `app`: `xi.ext.blocks.app` (server: `:ext.blocks/app-open` from `xi.ext.mcp` or `xi.ext.user.guard`, the resource, the frame's requests over `:server-fx`) and `xi.ext.blocks.frame` (browser: sandboxed iframe, JSON-RPC bridge); see [mcp-internals.md](mcp-internals.md#mcp-apps-sep-1865). User extensions add views with `:ui-resources` and `:ui` on a tool definition. |
| canvas-review | Experimental node-based review canvas (`canvas_review_*` tools), shown as the room's `:canvas` buffer (`/canvas-review` switches the originating client to it); has a web half. A code block's Explain button spawns a sub-agent (`:canvas-review/explain-start` fx, no confirmation) and stores its final text on the node through a `:subagent/turn-end` chain — canvas-review is composed before subagent in `xi.config`, so that chain runs first and reads the child's history directly; keep that order. |
| diff | `/diff` viewer buffer, one per source (`git` \| `staged` \| `unstaged` \| `session-edits` \| `session-git` \| `session-commits` \| `<ref>`; no args = session diff), id `diff:<source>` (`xi.buffers`); has a web half. A `difft` prefix renders with difftastic: `difft[:cols]` its ANSI text (TUI), `difft:json` rows for the web viewer — `GIT_EXTERNAL_DIFF` is a hook script (`xi.ext.diff.difft`) printing per file difftastic's JSON and both sides, parsed server-side by `xi.diff.difft` into `:split` rows (the buffer's `:rows`; `:text` their unified text). The web viewer renders any rows (`xi.diff/buffer-rows`: unified git text or `:rows`) in `:web/diff-layout` (`xi.diff/as-layout`, `:stacked` / `:split`, user state `:diff-layout`); a consumer of another diff tool only has to produce rows. |
| file-view | Opens files touched by write/edit into a `file:<path>` buffer; has a web half. |
| terminal | Terminal buffers `term:<id>` (`/term`, `:terminal/open`, rules `{:tool :terminal}`): a program under a Bun PTY (`xi.ext.terminal.pty`), shown by xterm.js (core `xi.web.terminal`, vendored in `resources/public/vendor/xterm`). Its I/O never enters the event loop on the browser side: keys go out through the transport's raw `:send!`, `:terminal/output` is taken off the socket by the transport's `:intercept`; on the server the roomless `:terminal/attach\|detach\|input\|resize` are `:no-broadcast` and output leaves through `:server-fx` `send!`. `:terminal/input` is coalesced in the event log (no keystrokes in `xi_events`). `:terminal/open :command` runs a script with `$SHELL -c` in a `:hold?` buffer (kept on exit 0), what a shell code block's Run in terminal (`:block-menu-items`, `core/block-command`) opens. Has a web half (forwarding, touch keys, `:web/terminals-gone`, that menu entry). |
| file-finder | Ctrl+P fuzzy file finder (TUI). |
| projects | `/project` / Alt+P project path picker; remembers the git repo of every room / `/cd`; list from `xi.projects` ([guide: configuration](guide/configuration.md#projects)). |
| skills | Project-marker system-prompt injection + `/skill list\|load` (`<input />` placeholders raise a `:form` dialog). |
| snippets | Insertable prompt snippets for the web client. |

**Notifications & terminal**

| Extension | What it does |
|-----------|--------------|
| terminal-title | Terminal title from session name / cwd. |
| clipboard-image | Pasted clipboard image paths → inline base64 images (event hook). |
