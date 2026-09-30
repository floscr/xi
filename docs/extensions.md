# Extensions

Xi extensions add behaviour via data maps composed at assembly time. There
are no registration atoms or global state — extensions are plain
ClojureScript maps wired through `xi.cli` into the core event loop,
provider effects, and TUI.

> Writing a new extension? Follow the step-by-step recipe in
> [writing-extensions.md](writing-extensions.md) — this file is the
> reference for every key.
>
> Want one without rebuilding xi? Drop it into `~/.config/xi/extensions/`.
> These **user extensions** load at runtime in a capability sandbox; see
> [user-extensions.md](user-extensions.md).

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
   :taps             [(fn [dispatch!] → tap-fn)]})
```

## Architecture

Which extensions load is declared in **`src/xi/config.cljc`** — plain
Clojure, one vector per surface (`server`, `client`, `web`):

```clojure
(def server
  [plan-mode/extension    ; extension map → used as-is
   pushover/create        ; factory fn → called with ctx by ext/instantiate
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
  snapshot, mirrors to clients. Used for: plan-mode `:enabled?`,
  done-notify `:enabled?`.
- **Process-local** `[:ext <id>]` — never crosses the wire. Used for:
  dictation `:recording?`.

Declare initial state via `:init`:

```clojure
{:init {:room    {:enabled? false}   ; per-room, mirrored
        :process {:recording? false}}} ; per-process, local
```

## Handler Contract

Handlers are pure: `(fn [state event]) → {:state :effects} | nil`.

Effects are data `[[:fx/type payload] …]`. Effect handlers receive
`{:dispatch! :get-state …}` — they perform side effects and may dispatch
new events.

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
resolves to `false` (safe default) when no client is attached. A second
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
extension tools by `:tool-name` (see [rules.md](rules.md)).

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
user's answer (or a safe default — `false`/`nil` — when the server is truly
headless). Dialog types:

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
:keybindings [{:key   "ctrl+shift+n"
               :event {:type :ext.done-notify/toggle}
               :when  (fn [state] ...)}]  ; optional guard
```

The TUI folds `:when` into the key detection function — a guarded binding
falls through to the editor's own handler when the guard fails. Events are
dispatched with `:room-id` added automatically.

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
;; pushover: nil when env vars missing → ext/compose drops it
(defn create [_ctx]
  (when (and user-key app-token)
    {:id :pushover ...}))

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
`xi.providers.anthropic/resolve-tooling`), so tool changes take effect on the
next turn without a restart.

**Scope:** only *use-time* surfaces hot-swap — `:tool-definitions`,
`:tool-registry`. Construction-time surfaces (`:handlers`,
`:event-hooks`, commands, `:keybindings`, `:system-prompt`, `:taps`,
`:routes`) are baked at assembly and need a restart to fully change. Design
runtime-toggleable extensions to contribute only tools.

**External MCP servers** are built on this: `xi.ext.mcp` wraps each
configured MCP server (`~/.config/xi/mcp.edn`) as an extension contributing
`mcp__<id>__*` tools and registers it into the manager. See
[mcp-servers.md](mcp-servers.md).

## Web Client Surface

Extensions can contribute routes, pages, navigation entries and taps to the
browser client. Because the web client is a separate shadow-cljs `:browser`
build, an extension with a web UI is split in two:

- `src/xi/ext/github.cljs` — the node/server half (tools, server handlers,
  roomless events), listed in `xi.config/server`.
- `src/xi/ext/github/web.cljs` — the browser half (routes, pages, client
  handlers), listed in `xi.config/web`. It may require
  `xi.web.views` (shared building blocks: `nav-group`, `overflow-menu`,
  `spinner`, `shorten-path`, `diff-rows-view`) and `ui.*` components, but
  core web namespaces never require extension code.

The two halves share an `:id` and talk over the same WS events
(e.g. `:pr/web-list` → `:pr/web-list-result`).

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
  Overflow items may carry `:mode` (`:project`, `:pr-detail`, …) to show
  only in a matching topbar context; the context's `:cwd`/`:room-id`/`:number`
  are merged into the `:event` on click.
- **`:taps`** — `(fn [dispatch!] → (fn [event state]))` factories, added via
  `add-tap!` at init (e.g. github's fire-pending-submit-after-`:room/joined`).

### Routing hooks

There is no on-enter callback: extensions chain a `:route/navigate`
handler after the base router handler (`ext/merge-handlers`), syncing
drill-down state from the route and emitting fetch effects:

```clojure
(defn- on-navigate [st {:keys [page cwd]}]
  (when (= page :pr-list)
    {:state   (assoc st :web/pr-cwd cwd)
     :effects (when (empty? (:web/pulls st))
                [[:app/dispatch {:type :pr/web-list}]])}))
```

Example: `xi.ext.github.web` (/pulls PR list/detail/diff pages).

## Writing a New Extension

Follow [writing-extensions.md](writing-extensions.md) — the step-by-step
recipe, contracts, register step, and a table of example extensions to copy.

## Built-in Extensions

Which of these load, and in what order, is `src/xi/config.cljc`; each
namespace docstring is the authoritative description.

**Policy & safety**

| Extension | What it does |
|-----------|--------------|
| rules | Declarative rules engine — every allow/deny/confirm policy; `/rules`. Loaded first. See [rules.md](rules.md). |
| plan-mode | Read-only exploration mode (`/plan`, 📋 badge). The read-only policy itself is a default rule. |

**Agent tools**

| Extension | What it does |
|-----------|--------------|
| clj | Sandboxed Clojure (SCI) scripting tool + `bb` tool — replaces bash. See [clj-tool.md](clj-tool.md). |
| process-manager | Registry of background processes started via clj's `process` ns; `/ps`, `/kill`. |
| treesitter | Large-file `read` → structural outline; `read_source`. See [treesitter.md](treesitter.md). |
| clj-surgeon | Structural Clojure refactoring tools; auto-fixes parens after write/edit. |
| commit | Hunk-level staging + commit tools; `/commit`. |
| kb | Knowledge base search/get/store via the `kb` CLI. |
| web | `fetch`: HTML→markdown, Jina fallback, feed parsing. |
| freesearch | Free `web_search` tool (no paid API, no headless browser). |
| product-search | `amazon_search` / `willhaben_search` / `geizhals_search` over a shared headless Chrome. |
| github-code-search | github.com code search (full query syntax); `/github-login`. |
| session-search | Search previous sessions by title and content. |
| events | Agent tool for inspecting the session event log. |
| subagent | Background sub-agents (`spawn_subagent` …); `/subagents`. |
| image-graph | Per-project Gemini image gallery (`GEMINI_API_KEY`); has a web half. |
| chrome | Proxies `chrome-devtools-mcp` as xi tools (opt-in, `XI_CHROME_TOOLS`). Hosts element-picker (`/pick`), design-mode (`/design`) and style-editor. See [chrome-mcp.md](chrome-mcp.md), [element-picker.md](element-picker.md), [design-mode.md](design-mode.md), [style-editor.md](style-editor.md). |
| mcp | Wraps external MCP servers (`~/.config/xi/mcp.edn`) as extensions; `/mcp`. See [mcp-servers.md](mcp-servers.md). |
| render | Render.com MCP server, disabled by default; `/render`. |
| extensions | `/ext list\|enable\|disable\|reload` over the live extension manager (`reload` re-reads [user extensions](user-extensions.md)). |

**Sessions, review & workflow**

| Extension | What it does |
|-----------|--------------|
| resume | `/trim`, `/rollover`, `/lineage`. See [resume.md](resume.md). |
| worktree | `/worktree` — move the room into a fresh git worktree (`merge`/`list`/`remove`). |
| review | `/review [staged\|<ref>]` — code review prompt with per-language checklists. |
| canvas-review | Experimental node-based review canvas (`canvas_review_*` tools); has a web half. |
| diff | `/diff` viewer buffer (see [commands.md](commands.md)); has a web half. |
| file-view | Opens files touched by write/edit into a `:file` buffer; has a web half. |
| file-finder | Ctrl+P fuzzy file finder (TUI). |
| github | Roomless PR browsing via `gh`; web half: /pulls list/detail/diff. |
| projects | `/project` / Alt+P project path picker. |
| skills | Project-marker system-prompt injection + `/skill list\|load` (`<input />` placeholders raise a `:form` dialog). |
| snippets | Insertable prompt snippets for the web client. |
| browser-open | `/browser-open` — open this session in the web client. |

**Notifications & terminal**

| Extension | What it does |
|-----------|--------------|
| done-notify | Desktop notification on turn end / pending dialog. Ctrl+Shift+N, 🔔 badge. |
| pushover | Pushover push (factory; inert without keys). Ctrl+Shift+P cycles auto / on / off per room. |
| terminal-title | Terminal title from session name / cwd. |
| clipboard-image | Pasted clipboard image paths → inline base64 images (event hook). |
| dictation | Client-only voice input via sox/whisper (Alt+R). |
