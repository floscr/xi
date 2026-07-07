# Writing a Xi Extension

Step-by-step recipe. Key reference: [extensions.md](extensions.md).

## Rules

- An extension is a plain data map, composed by `ext/compose`. No atoms, no
  registration, no side effects at load.
- Handlers are pure `(fn [state event]) → {:state :effects} | nil`. I/O only
  in `:fx` handlers and tool fns.
- State lives in app state: room-scoped `[:rooms rid :ext <id>]` (mirrored to
  clients) or process-local `[:ext <id>]`.
- Your handlers chain AFTER the base handler for the same event type.

## 1. Decide

| I want to… | Key |
|---|---|
| New agent tool | `:tool-definitions` + `:tool-registry` |
| `/slash` command | `:commands` |
| React to events | `:handlers` |
| Side effects | `:fx` |
| Rewrite/block events pre-dispatch | `:event-hooks` |
| Intercept/confirm tool calls | `:tool-gate` |
| System prompt text | `:system-prompt` |
| TUI shortcut | `:keybindings` |
| Prompt indicator | `:prompt-badge` |
| Initial state | `:init` |
| Browser UI | `:routes` `:pages` `:nav-items` `:taps` |
| WS server hooks | `:server-fx` `:roomless-events` `:no-broadcast` |
| Exit cleanup | `:on-shutdown` |

- Server extension for anything touching rooms/agent/tools; client extension
  only for process-local TUI concerns.
- Needs runtime config? Make it a factory `(defn extension [] …)` returning
  `nil` when unconfigured — compose drops nils.

## 2. Create

File `src/xi/ext/my_thing.cljs`, ns `xi.ext.my-thing`, events namespaced
`:ext.my-thing/*`.

```clojure
(ns xi.ext.my-thing
  "What it does, how it's triggered, where state lives."
  (:require [xi.core.state :as state]))

(def ^:private ext-id :my-thing)

(def extension
  {:id ext-id
   ;; only the keys you need
   })
```

## 3. Contracts

```clojure
;; handler — pure, runs after base handler
(defn- on-turn-end [st {:keys [room-id aborted?]}]
  (when (and (not aborted?) (enabled? st room-id))
    {:effects [[:my-thing/notify {:room-id room-id}]]}))

;; fx — side effects; report back by dispatching
(defn- notify [{:keys [dispatch!]} {:keys [room-id]}]
  (-> (do-io!)
      (.then #(dispatch! {:type :ext.my-thing/done :room-id room-id}))))

;; tool — args keywordized; result map or Promise of it
(fn [{:keys [query]} {:keys [cwd]}]
  {:content [{:type "text" :text "…"}] :is-error false})

;; command — pure; I/O via effects
{:name "mything" :description "…"
 :handler (fn [_st {:keys [room-id args]}]
            {:effects [[:my-thing/start {:room-id room-id :args args}]]})}

;; tool-gate — tool-call (allow) | nil (block) | {:intercepted true :result …} | Promise
;; ctx has :confirm! (fn [msg] → Promise<bool>), false when no client attached

;; event-hook — (fn [event state] → event'|nil); nil blocks; skipped on :remote?

;; init
{:init {:room {:enabled? false}}}      ; mirrored; read via state/room-ext
{:init {:process {:recording? false}}} ; local; read via (get-in st [:ext ext-id])
```

## 4. Register

- Server: `server-extensions` in `src/xi/cli.cljs`
- Client: `client-extensions` in `src/xi/cli.cljs`
- Web half: `web-extensions` in `src/xi/web/core.cljs`

## 5. Web Half (browser UI only)

Split in two, same `:id`:

- `src/xi/ext/my_thing.cljs` — node/server half (tools, server handlers,
  `:roomless-events` for events sent without a room)
- `src/xi/ext/my_thing/web.cljs` — browser half; no node APIs. May require
  `xi.web.views` and `ui.*` (always use `ui.*` components, never raw
  `[:input]`/`[:button]`). Core web namespaces never require extension code.

Halves talk over normal WS events (`:pr/web-list` → `:pr/web-list-result`).

No on-enter callback — chain a `:route/navigate` handler:

```clojure
(defn- on-navigate [st {:keys [page file]}]
  (when (= page :my-page)
    {:state   (assoc st :web/my-file file)
     :effects (when (empty? (:web/my-items st))
                [[:app/dispatch {:type :my-thing/web-list}]])}))
```

- `:routes` — keyed by first URL segment; `:parse` gets remaining segments →
  `{:page kw …}`; `:path` maps `page-kw → (fn [route] → url)`;
  `:roomless-pages` only if entering should leave the active room.
- `:pages` — `{page-kw (fn [state dispatch!] → hiccup)}`.
- `:nav-items` — data-only; `:menu` ∈ `:sidebar :palette :home-topbar
  :overflow`; overflow items may set `:mode`, ctx keys are merged into
  `:event` on click.
- Replicant: seq-rendered siblings need `:replicant/key`.

Canonical examples: `src/xi/ext/gtd/web.cljs`, `src/xi/ext/github/web.cljs`.

## 6. Verify

1. `bb check` first. If a watch is running, don't `bb build` — save and check
   the watch pane (`tmux capture-pane -t xi-serve:1.1 -p`). New namespace
   "not available"? `touch` the new file and its requirer.
2. `bb test` (tests in `test/xi/ext/my_thing_test.cljs`, auto-discovered).
3. Server-side changes: `bb serve:restart`. Web half: browser refresh.
4. Web half: test in browser at `http://localhost:7474` — nav items, deep
   link, reload, back/forward, console clean, chat regression.
5. TUI can't be agent-tested — compile and say so. `xi prompt "…"` is safe
   for one-shot tool checks.

## Pitfalls

- Atoms for state → use `:init` + app state
- I/O in handlers/commands → use effects
- `js/process.env.KEY` → `(aget js/process.env "KEY")`
- Bare string tool result → `{:content [{:type "text" :text …}]}`
- Node APIs in the web half
- Forgetting to register in `xi.cli` / `xi.web.core`
- Starting/killing servers by hand → `bb` tasks only

## Examples

| Need | Read |
|---|---|
| Handlers + fx + keybinding + badge + room state | `src/xi/ext/done_notify.cljs` |
| Tools only | `src/xi/ext/kb.cljs` |
| Tool gate with confirm | `src/xi/ext/permission_gate.cljs` |
| Event hook | `src/xi/ext/clipboard_image.cljs` |
| Conditional system prompt | `src/xi/ext/skills.cljs` |
| Factory (env-configured) | `src/xi/ext/pushover.cljs` |
| Two-build web extension | `src/xi/ext/github.cljs` + `github/web.cljs` |
