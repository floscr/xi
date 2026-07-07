# Extensions

Xi extensions add behaviour via data maps composed at assembly time. There
are no registration atoms or global state — extensions are plain
ClojureScript maps wired through `xi.cli` into the core event loop,
provider effects, and TUI.

## Extension Shape

```clojure
(def extension
  {:id               :my-ext           ; keyword (required)
   :init             {:room    {...}    ; template merged into each room's [:ext :my-ext]
                      :process {...}}   ; installed at top-level [:ext :my-ext]
   :handlers         {event-type handler-fn}  ; chained AFTER base handlers
   :fx               {fx-type (fn [ctx payload])}
   :event-hooks      {event-type (fn [event state] → event'|nil)}
   :tool-gate        (fn [tool-call ctx] → tool-call|nil|{:intercepted ...})
   :tool-definitions [{:name :description :input_schema}]
   :tool-registry    {name (fn [args ctx] → result|Promise)}
   :commands         [{:name :description :handler}]
   :system-prompt    str | (fn [cwd] → str|nil)
   :keybindings      [{:key "alt+r" :event {...} :when (fn [state])}]
   :prompt-badge     (fn [state] → str|nil)
   :on-shutdown      (fn [])
   ;; web-client surface (browser build only — see "Web Client Surface")
   :routes           {"seg" {:parse fn :path {page-kw fn} :roomless-pages #{page-kw}}}
   :pages            {page-kw (fn [state dispatch!] → hiccup)}
   :nav-items        [{:menu :sidebar|:palette|:home-topbar|:overflow ...}]
   :taps             [(fn [dispatch!] → tap-fn)]})
```

## Architecture

```
xi.cli (assembly)
├── server-extensions  →  ext/compose  →  composed map
│     plan-mode, done-notify, pushover,     │
│     kb, web, perplexity, commit,          │  merge into
│     clj-surgeon, gtd, permission-gate,    │  app handlers,
│     todo-intercept, terminal-title,       │  fx, commands,
│     clipboard-image, projects, skills     │  tool-gate, etc.
├── client-extensions  →  ext/compose       │
│     dictation                             │
└── create-app ←────────────────────────────┘
      ↕ events      ↕ effects
    handlers        fx handlers
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
`:confirm!` is `(fn [message] → Promise<bool>)` — raises a TUI dialog;
resolves to `false` (safe default) when no client is attached.

Tool exec-fns only receive `{:cwd}` — they have no access to dispatch,
state, or dialogs. Approval/interception must happen in the gate.

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

Extensions that need runtime configuration return nil when unconfigured:

```clojure
;; pushover: nil when env vars missing → ext/compose drops it
(defn extension []
  (when (and user-key app-token)
    {:id :pushover ...}))
```

## Web Client Surface

Extensions can contribute routes, pages, navigation entries and taps to the
browser client. Because the web client is a separate shadow-cljs `:browser`
build, an extension with a web UI is split in two:

- `src/xi/ext/github.cljs` — the node/server half (tools, server handlers,
  roomless events), composed in `xi.cli`.
- `src/xi/ext/github/web.cljs` — the browser half (routes, pages, client
  handlers), composed in `xi.web.core/web-extensions`. It may require
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
  `add-tap!` at init (e.g. GTD's fire-pending-task-after-`:room/joined`).

### Routing hooks

There is no on-enter callback: extensions chain a `:route/navigate`
handler after the base router handler (`ext/merge-handlers`), syncing
drill-down state from the route and emitting fetch effects:

```clojure
(defn- on-navigate [st {:keys [page file task-id]}]
  (when (= page :gtd)
    {:state   (assoc st :web/gtd-file file :web/gtd-task-id task-id)
     :effects (when (empty? (:web/gtd-tasks st))
                [[:app/dispatch {:type :gtd/web-list}]])}))
```

Examples: `xi.ext.gtd.web` (/gtd pages, task launcher), `xi.ext.github.web`
(/pulls PR list/detail/diff pages).

## Writing a New Extension

1. Create `src/xi/ext/my_ext.cljs`
2. Define the extension map with an `:id`
3. Add to `server-extensions` or `client-extensions` in `xi.cli`
4. Build: `bb build` — shadow-cljs compiles it in

### Minimal example

```clojure
(ns xi.ext.my-ext)

(defn- my-tool [{:keys [query]} {:keys [cwd]}]
  (js/Promise.resolve
   {:content [{:type "text" :text (str "Hello from " cwd)}]}))

(def extension
  {:id               :my-ext
   :tool-definitions [{:name "my_tool"
                       :description "A sample tool"
                       :input_schema {:type "object"
                                      :properties {:query {:type "string"}}
                                      :required ["query"]}}]
   :tool-registry    {"my_tool" my-tool}})
```

### Extension with state, commands, and badges

```clojure
(ns xi.ext.my-ext
  (:require [xi.core.state :as state]))

(def ^:private ext-id :my-ext)

(defn- enabled? [st room-id]
  (boolean (:enabled? (state/room-ext st room-id ext-id))))

(defn- toggle [st {:keys [room-id]}]
  (when (state/get-room st room-id)
    (let [st' (update-in st [:rooms room-id :ext ext-id :enabled?] not)
          on? (get-in st' [:rooms room-id :ext ext-id :enabled?])]
      {:state (update-in st' [:rooms room-id :history] conj
                         {:kind :status :text (str "My ext: " (if on? "ON" "OFF"))})})))

(def extension
  {:id           ext-id
   :init         {:room {:enabled? false}}
   :commands     [{:name "myext"
                   :description "Toggle my extension"
                   :handler toggle}]
   :prompt-badge (fn [state]
                   (when-let [room (state/active-room state)]
                     (when (enabled? state (:id room)) " ⚡")))})
```

## Built-in Extensions

| Extension | Type | Description |
|-----------|------|-------------|
| plan-mode | tool-gate, command, badge | Read-only exploration mode (`/plan`). Blocks writes except tasks/todo.md. |
| done-notify | handler, keybinding, badge | Desktop notification on turn end. Ctrl+Shift+N toggle, 🔔 badge. |
| pushover | handler (factory) | Pushover notification when no visible client attached. |
| dictation | handler, keybinding, badge (factory, client-only) | Voice input via sox/whisper. Alt+R to record. |
| permission-gate | tool-gate | Confirms writes to sensitive paths and dangerous bash commands. |
| todo-intercept | tool-gate | Intercepts writes to tasks/todo.md → GTD captures. |
| kb | tools | Knowledge base search/get/store via `kb` CLI. |
| web | tools | Fetch URLs with HTML→markdown, Jina fallback, feed parsing. |
| perplexity | tools, command | Web search via Perplexity; `/perplexity-login` to authenticate. |
| commit | tools, command, tool-gate | Git workflow; `/commit` builds a prompt from live overview. Commit requires user confirmation via tool-gate. |
| clj-surgeon | tools, handler | Structural Clojure refactoring. Auto-fixes parens after write/edit to .clj files. |
| gtd | tools, command, handler, system-prompt, web | GTD task management. `/gtd` picker, `/gtd recommend`, `/gtd cleanup`. Web half: /gtd pages + task launcher. |
| github | handler, web | Roomless PR browsing via `gh`. Web half: /pulls list/detail/diff pages + review-with-agent. |
| terminal-title | handler | Sets terminal title from session name/cwd via ANSI escape. |
| clipboard-image | event-hook | Converts pasted clipboard image paths to inline base64. |
| projects | command, handler, keybinding | Project path picker. `/project` or Alt+P. |
| skills | system-prompt, command | Injects tool knowledge based on project markers; `/skill list\|load`. |
