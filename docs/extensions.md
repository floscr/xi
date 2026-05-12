# Extensions

Xi extensions add behaviour via hooks, tools, and commands. They are registered at startup in `runtime.cljs` and dispatched through `ext/core.cljs`.

## Architecture

```
runtime.cljs                          ext/core.cljs
┌──────────────────────┐              ┌─────────────────────────────┐
│  sync-hook-state!    │──set-state!─→│  hook-state atom            │
│  (session, model,    │              │  {:session {:name ...}      │
│   cwd, effort)       │              │   :model  :cwd  :effort}   │
└──────────────────────┘              └──────────┬──────────────────┘
                                                 │ auto-injected
                                    ┌────────────┴──────────────┐
                                    ↓            ↓              ↓
                              dispatch-hook  dispatch-hook   collect-
                                             -transform     prompt-badges
                                    ↓            ↓              ↓
                              ┌──────────────────────────────────────┐
                              │          Extension Hooks             │
                              │  done-notify  permission-gate  ...   │
                              └──────────────────────────────────────┘
```

## Extension Shape

```clojure
(def extension
  {:name "my-ext"
   :hooks    {<event-kw> handler-fn ...}
   :tools    [{:name ... :description ... :input_schema ... :execute fn}]
   :commands [{:name ... :description ... :handler fn}]})
```

Register in `runtime.cljs`:

```clojure
(ext/register-extension! my-ext/extension)
```

## Hook State

All hooks receive a **state map** as their argument. The runtime populates this state automatically — extensions never need to assemble it themselves.

### State shape

```clojure
{:session {:name "refactor auth module"
           :id   "019abc-..."
           :cli-session-id "sess_xyz"
           :cwd  "/home/user/project"
           :created "2026-05-12T..."}
 :model   "claude-sonnet-4-20250514"
 :effort  "high"
 :cwd     "/home/user/project"}
```

### Accessor functions (`xi.state.session`)

Use these instead of reaching into the map directly:

```clojure
(require '[xi.state.session :as state.session])

(state.session/session-title state)   ;; "refactor auth module" or nil
(state.session/session-id state)      ;; "019abc-..."
(state.session/cli-session-id state)  ;; "sess_xyz"
(state.session/cwd state)             ;; "/home/user/project"
(state.session/model state)           ;; "claude-sonnet-4-20250514"
```

### When state is synced

The runtime calls `sync-hook-state!` at two points:
1. **Startup** — after `runtime/create!` initialises session, model, cwd
2. **After each turn** — session name and cli-session-id may have been updated

Event-specific context can be merged on top via the second arg to `dispatch-hook`:

```clojure
(ext/dispatch-hook :agent-end)                     ;; just hook state
(ext/dispatch-hook :agent-end {:extra "context"})  ;; merged on top
```

## Hook Types

### Lifecycle hooks — `dispatch-hook`

Called at lifecycle points. Receive `state`. Return value is ignored.

```clojure
(defn- on-agent-end [state]
  (let [title (state.session/session-title state)]
    (notify! title)))
```

**Events:** `:session-start` `:session-shutdown` `:turn-start` `:turn-end` `:before-agent-start` `:agent-end` `:tool-result` `:tool-execution-end`

### Transform hooks — `dispatch-hook-transform`

Called in a chain. Each handler receives `(value, state)` and returns the (possibly modified) value. Return `nil` to block the chain.

```clojure
(defn- gate-tool [tool-call state]
  (if (dangerous? tool-call)
    nil          ;; block
    tool-call))  ;; pass through
```

**Events:** `:tool-call` `:context` `:input`

### Async transform hooks — `dispatch-hook-transform-async`

Like `dispatch-hook-transform` but supports handlers that return Promises. Always returns a Promise. Used for `:tool-call` gating where confirmation dialogs need async user input.

```clojure
(defn- gate-tool [tool-call state]
  (if (dangerous? tool-call)
    (-> (ext/confirm! "Allow dangerous operation?")
        (.then (fn [allowed?] (if allowed? tool-call nil))))
    tool-call))  ;; sync return also works
```

### Prompt badge hooks — `collect-prompt-badges`

Special query hook. Each handler receives `state` and returns a string (or nil). All non-nil results are concatenated and displayed after the `xi>` prompt.

```clojure
(defn- prompt-badge [_state]
  (when @enabled "🔔"))
```

### Async hooks — `dispatch-hook-async`

Like `dispatch-hook` but handlers may return promises. Returns a single promise that resolves when all handlers complete.

## Writing an Extension

### Minimal example

```clojure
(ns xi.ext.my-ext)

(defn- on-agent-end [state]
  (println "Turn done for:" (:cwd state)))

(def extension
  {:name "my-ext"
   :hooks {:agent-end on-agent-end}})
```

### Extension with tools, commands, and badges

```clojure
(ns xi.ext.my-ext
  (:require [xi.state.session :as state.session]))

(defonce ^:private active (atom false))

(defn toggle! [] (swap! active not))

(defn- on-agent-end [state]
  (when @active
    (println "Done:" (state.session/session-title state))))

(defn- badge [_state]
  (when @active "⚡"))

(def extension
  {:name "my-ext"
   :hooks {:agent-end on-agent-end
           :prompt-badge badge}
   :commands [{:name "myext"
               :description "Toggle my extension"
               :handler (fn [_ctx] (toggle!) nil)}]})
```

### Registering

Add to `runtime.cljs`:

```clojure
(:require [xi.ext.my-ext :as ext-my-ext])

;; In register-extensions!
(ext/register-extension! ext-my-ext/extension)
```

## Built-in Extensions

| Extension | Hooks | Description |
|-----------|-------|-------------|
| `done-notify` | `:agent-end` `:prompt-badge` | Desktop notification via dunstify on turn end. Toggle with Ctrl+Shift+N, shows 🔔 badge. Middle-click notification to focus terminal. |
| `permission-gate` | `:tool-call` | Guards writes to sensitive paths (.ssh, .env, .git), dangerous bash commands, and `git push` with user confirmation. |
| `plan-mode` | `:context` `:tool-call` | Read-only exploration mode. `/plan` toggles. Blocks writes except `tasks/todo.md`. |
| `terminal-title` | `:session-start` `:turn-end` | Sets terminal title to session name via ANSI escape. |
| `parmezan` | `:tool-execution-end` | Runs parmezan CLI to fix unbalanced delimiters in Clojure files after writes. |
| `kb` | (tools only) | Knowledge base search/get/store via `kb` CLI. |
| `commit` | (tools+commands only) | Git commit workflow with hunk-level staging. |
| `web` | (tools only) | Fetch URLs and return cleaned content. |

## Prompt Badges

Extensions can display indicators in the input prompt line after `xi>`. The editor calls `collect-prompt-badges` on every render.

The editor accepts `:prompt-suffix-fn` — a zero-arg function returning the badge string. The TUI wires this to `ext/collect-prompt-badges`.

```
xi> 🔔 _                  ← notification enabled
xi> _                      ← no badges
```

Badges support ANSI escape codes for coloring.

## Keybinding Integration

The TUI editor supports extension-triggered keybindings via callbacks:

| Keybinding | Callback | Used by |
|------------|----------|---------|
| Ctrl+Shift+N | `:on-notify-toggle` | done-notify (toggle 🔔) |
| Ctrl+Shift+G | `:on-git` | git status |

To add a new keybinding:
1. Add key detection in `tui/editor.cljs` (CSI u format: `ESC[<codepoint>;6u` for Ctrl+Shift)
2. Add callback option in `make-editor`
3. Wire in `client/tui.cljs`

## API Reference

```clojure
(require '[xi.ext.core :as ext])

;; State management (called by runtime)
(ext/set-state! state-map)
(ext/update-state! partial-map)
(ext/get-state)

;; Hook dispatch
(ext/dispatch-hook :event)
(ext/dispatch-hook :event {:extra "ctx"})
(ext/dispatch-hook-transform :event initial-value)
(ext/dispatch-hook-transform :event initial-value {:extra "ctx"})
(ext/dispatch-hook-transform-async :event initial-value)  ;; Promise-aware
(ext/dispatch-hook-transform-async :event initial-value {:extra "ctx"})
(ext/dispatch-hook-async :event)
(ext/collect-prompt-badges)

;; Confirmation (for permission gates / interactive approval)
(ext/set-confirm-handler! (fn [message] ...))  ;; called by TUI at startup
(ext/confirm! "Allow this?")                    ;; returns Promise<boolean>

;; Registration
(ext/register-extension! ext-map)
(ext/list-extensions)
(ext/list-commands)
(ext/get-command "name")

;; Tool access
(ext/get-ext-tool-definitions)
(ext/get-ext-tool-registry)
```
