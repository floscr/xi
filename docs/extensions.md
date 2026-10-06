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
> `test/xi/ext/user_test.cljs`.

## Extension Shape

```clojure
(def extension
  {:id               :my-ext           ; keyword (required)
   :init             {:room    {...}    ; template merged into each room's [:ext :my-ext]
                      :process {...}}   ; installed at top-level [:ext :my-ext]
   :handlers         {event-type handler-fn}  ; chained AFTER base handlers
   :fx               {fx-type (fn [ctx payload])}
   :event-hooks      {event-type (fn [event state] → event'|nil)}
   :tool-definitions [{:name :description :input_schema}]
   :tool-registry    {name (fn [args ctx] → result|Promise)} ; ctx: {:cwd :client-pid :dispatch! :get-state :room-id :confirm!}
   :remove-tools     #{tool-name}      ; builtin tools to hide from the model
   :commands         [{:name :description :handler}]
   :system-prompt    str | (fn [cwd] → str|nil)
   :keybindings      [{:key "alt+r" :event {...} :when (fn [state])}]
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
   ;; web-client surface (browser build only — see "Web Client Surface")
   :routes           {"seg" {:parse fn :path {page-kw fn} :roomless-pages #{page-kw}}}
   :pages            {page-kw (fn [state dispatch!] → hiccup)}
   :nav-items        [{:menu :sidebar|:palette|:home-topbar|:overflow ...}]
   :sidebar-groups   [{:id kw :label str :where session-key :limit n :more {...}}]
   :session-menu-items [{:label str :label-on str :flag session-key :icon kw :event {...}}]
   :taps             [(fn [dispatch!] → tap-fn)]})
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
  snapshot, mirrors to clients. Used for: plan-mode `:enabled?`.
- **Process-local** `[:ext <id>]` — never crosses the wire. Used for:
  the rules engine's server-session rules.

Declare initial state via `:init`:

```clojure
{:init {:room    {:enabled? false}   ; per-room, mirrored
        :process {:rules []}}}       ; per-process, local
```

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

Tool exec-fns get the same context plus the provider's own keys:
`{:cwd :client-pid :dispatch! :get-state :room-id :confirm!}` (in a
sub-agent turn, `:room-id` is the parent room and `:confirm!` auto-denies).
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

Declarative key → event dispatch:

```clojure
:keybindings [{:key   "alt+r"
               :event {:type :ext.notes/toggle}
               :when  (fn [state] ...)}]  ; optional guard
```

The TUI folds `:when` into the key detection function — a guarded binding
falls through to the editor's own handler when the guard fails. Events are
dispatched with `:room-id` added automatically. Built-in TUI keys (not
extension-contributed): Ctrl+O toggles the system-prompt buffer's preview,
Alt+N starts a new chat in the current cwd (`/new`).

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

## Web Client Surface

Extensions can contribute routes, pages, navigation entries and taps to the
browser client. Because the web client is a separate shadow-cljs `:browser`
build, an extension with a web UI is split in two:

- `src/xi/ext/canvas_review.cljs` — the node/server half (tools, server
  handlers, state), listed in `xi.config/server`.
- `src/xi/ext/canvas_review/web.cljs` — the browser half (routes, pages,
  client handlers), listed in `xi.config/web`. It may require
  `xi.web.views` (shared building blocks: `nav-group`, `overflow-menu`,
  `spinner`, `shorten-path`, `diff-rows-view`) and `ui.*` components, but
  core web namespaces never require extension code.

The two halves share an `:id` and talk over the same WS events (the room's
mirrored `[:ext <id>]` state plus any request/reply pair they define).

### Keys

- **`:routes`** — `{"first-url-segment" entry}`. `(:parse entry)` receives
  the remaining path segments and returns a route map (`{:page kw …}`);
  `(:path entry)` maps `page-kw → (fn [route] → url-path)` for
  `route->path`; `:roomless-pages` is a set of pages that imply leaving the
  active room on navigation (unioned with the base `#{:home}`).
- **`:pages`** — `{page-kw (fn [state dispatch!] → hiccup)}`; `root-view`
  consults this table before its built-in cases.
- **`:nav-items`** — data-only entries `{:menu … :label … :icon … :event …}`
  rendered by core views; stored in app state at `:web/nav-items` during
  init. Menus: `:sidebar`, `:palette` (Cmd+K), `:home-topbar`, `:overflow`.
  Overflow items may carry `:mode` (`:project`, `:room`, …) to show only in
  a matching topbar context; the context's `:cwd`/`:room-id` are merged into
  the `:event` on click.
- **`:sidebar-groups`** — data-only groups of the drawer sidebar, shown
  between Drafts and Recent: `{:id kw :label str :where session-key
  :limit n :more {:label :icon :event}}`. A group lists the lobby's sessions
  whose `:where` key is truthy (a per-user flag the server puts on each
  session, such as `:favorite?`), most recently visited first, at most
  `:limit` of them; `:more` is a closing row that appears once there are more
  sessions than the limit. Empty groups are not drawn. The sessions also stay
  in their Recent / Earlier group, so ALT+j/k skips the extension groups. The
  group collapses like the core ones (its `:id` is the collapse key).
- **`:session-menu-items`** — data-only entries of every session card's
  context menu (right-click, long-press, the ⋮ button), listed above the core
  ones: `{:label str :label-on str :flag session-key :icon kw :event {…}}`.
  `:event` is dispatched with the card's `:session-id` merged in; `:label-on`
  replaces `:label` while the card's `:flag` key is truthy (Add to favorites →
  Remove from favorites).
- **`:taps`** — `(fn [dispatch!] → (fn [event state]))` factories, added via
  `add-tap!` at init (e.g. fire a stashed action once `:room/joined` arrives).

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

Example: `xi.ext.canvas-review.web` (the /canvas-review page).

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

**Agent tools**

| Extension | What it does |
|-----------|--------------|
| clj | Sandboxed Clojure (SCI) scripting tool + `bb` tool — replaces bash. User docs: [clj tool reference](guide/clj-tool-reference.md); worker and gate internals in [architecture.md](architecture.md#the-clj-tools-worker). |
| process-manager | Registry of background processes started via clj's `process` ns; `/ps`, `/kill`. |
| treesitter | Large-file `read` → structural outline; `read_source`. See [treesitter.md](treesitter.md). |
| clj-surgeon | Structural Clojure refactoring tools; auto-fixes parens after write/edit. |
| commit | Hunk-level staging + commit tools; `/commit`. |
| session-search | Search previous sessions by title and content. |
| events | Agent tool for inspecting the session event log. |
| subagent | Background sub-agents (`spawn_subagent` …); `/subagents`. |
| mcp | Wraps external MCP servers (`~/.config/xi/mcp.edn`) as extensions; `/mcp`. See [mcp-internals.md](mcp-internals.md). |
| extensions | `/ext list\|enable\|disable\|reload` over the live extension manager (`reload` re-reads [user extensions](guide/extensions.md); agents get the same as the `ext_reload` tool). |

**Sessions, review & workflow**

| Extension | What it does |
|-----------|--------------|
| resume | `/trim`, `/rollover`, `/lineage`. See [architecture.md](architecture.md#session-tools) and the guide's [sessions](guide/sessions.md). |
| worktree | `/worktree` — move the room into a fresh git worktree (`merge`/`list`/`remove`). |
| canvas-review | Experimental node-based review canvas (`canvas_review_*` tools); has a web half. |
| diff | `/diff` viewer buffer (`git` \| `staged` \| `unstaged` \| `session-edits` \| `session-git` \| `session-commits` \| `<ref>`; no args = session diff); has a web half. |
| file-view | Opens files touched by write/edit into a `:file` buffer; has a web half. |
| favorites | Web only (`xi.ext.favorites.web`): the sidebar's Favorites group (5 most recent), "Add to favorites" in the session context menu, and the ⋮ menu entry opening the Favorites view. The stars themselves are per-user core state ([architecture.md](architecture.md#users)). |
| file-finder | Ctrl+P fuzzy file finder (TUI). |
| projects | `/project` / Alt+P project path picker; remembers the git repo of every room / `/cd`; list from `xi.projects` ([guide: configuration](guide/configuration.md#projects)). |
| skills | Project-marker system-prompt injection + `/skill list\|load` (`<input />` placeholders raise a `:form` dialog). |
| snippets | Insertable prompt snippets for the web client. |

**Notifications & terminal**

| Extension | What it does |
|-----------|--------------|
| terminal-title | Terminal title from session name / cwd. |
| clipboard-image | Pasted clipboard image paths → inline base64 images (event hook). |
