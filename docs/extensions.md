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
   :hooks       {<event-kw> handler-fn ...}
   :tools       [{:name ... :description ... :input_schema ... :execute fn}]
   :commands    [{:name ... :description ... :handler fn}]
   :keybindings [{:key "alt+x" :handler (fn [] ...)}]})
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
| `web` | (tools only) | Fetch URLs with UA rotation, HTML→markdown, Jina Reader fallback, feed parsing. |
| `perplexity` | (tools+commands) | Web search via Perplexity Pro/Max subscription. Token shared with Pi. |
| `projects` | (commands+keybindings) | Fuzzy project picker with file drill-down. `/project` or Alt+P. |

## Web Tools

Xi exposes two web tools to the agent via MCP:

### `web_search` (perplexity extension)

Search the web using your Perplexity Pro/Max subscription. Returns an AI-generated answer with cited sources.

```
Parameters:
  query    (string, required) — search query
  recency  (string, optional) — "hour" | "day" | "week" | "month" | "year"
  limit    (number, optional) — max sources to return (1-50)
```

**Authentication:** Uses OAuth token cached at `~/.config/pi-perplexity/auth.json` (shared with Pi). Falls back to macOS Perplexity desktop app token extraction. Run `/perplexity-login` to authenticate, `/perplexity-login --force` to re-authenticate.

**Implementation:** Uses a Bun subprocess to make HTTP requests (Bun's fetch passes Cloudflare; Node's gets challenged). Parses Perplexity's SSE stream and merges incremental events into a final answer + sources.

### `fetch` (web extension)

Retrieve content from a URL and return it in clean, readable format.

```
Parameters:
  url      (string, required) — URL to fetch
  timeout  (number, optional) — timeout in seconds (default: 20, range: 5-60)
  raw      (boolean, optional) — return raw HTML without transforms
```

**Features:**
- **UA rotation** — cycles through 3 user agents (curl, TextBot, Chrome) to bypass bot detection
- **HTML→Markdown** — converts headings, links, lists, code blocks, bold/italic
- **Jina Reader fallback** — tries `r.jina.ai` for higher quality rendering before built-in conversion
- **Bot-blocking detection** — retries with different UA on 403/503 with cloudflare/captcha responses
- **RSS/Atom feed parsing** — extracts titles and links from feed items
- **Low-quality detection** — detects JS-gated pages and navigation-heavy junk
- **Output truncation** — 300 lines / 100k chars

## TUI Bridge

Extensions can interact with the TUI editor through a bridge API. The TUI registers handlers at startup; extensions call them through `ext/core`.

### Completion menus

Show a fuzzy-filterable completion menu from any extension:

```clojure
(ext/show-completion!
 {:items [{:label "Display text" :value "actual-value"}]
  :prompt "pick> "
  :on-select (fn [item] (ext/insert-text! (:value item)))
  :key-bindings [{:key-fn (fn [data] (= data "\t"))
                  :handler (fn [state-atom update-items!] ...)}]})
```

The `:key-bindings` option allows custom key handling within the menu (e.g. Tab to drill into a subdirectory).

### Text insertion

Insert text at the editor cursor:

```clojure
(ext/insert-text! "/path/to/file")
```

Both functions are no-ops when no TUI client is connected (e.g. headless server mode).

## Prompt Badges

Extensions can display indicators in the input prompt line after `xi>`. The editor calls `collect-prompt-badges` on every render.

The editor accepts `:prompt-suffix-fn` — a zero-arg function returning the badge string. The TUI wires this to `ext/collect-prompt-badges`.

```
xi> 🔔 _                  ← notification enabled
xi> _                      ← no badges
```

Badges support ANSI escape codes for coloring.

## Keybinding Integration

Extensions can declare keybindings directly in their extension map using human-readable key descriptors. The system supports two mechanisms:

### Declarative keybindings (extension-defined)

Extensions declare `:keybindings` in their extension map:

```clojure
(def extension
  {:name "my-ext"
   :keybindings [{:key "alt+p"
                  :handler (fn [] (do-something!))}]})
```

Supported key descriptors:
- `alt+<char>` — e.g. `"alt+p"` (matches both ESC-prefix and kitty protocol)
- `ctrl+shift+<char>` — e.g. `"ctrl+shift+n"` (kitty CSI u format)

The `ext/core` module parses descriptors into terminal escape sequence matchers at registration time. The TUI editor checks extension keybindings after built-in bindings via `ext/get-keybindings`.

### Hard-wired keybindings (TUI callbacks)

Some keybindings are wired directly in the TUI via editor callbacks:

| Keybinding | Callback | Used by |
|------------|----------|---------|
| Ctrl+Shift+N | `:on-notify-toggle` | done-notify (toggle 🔔) |
| Ctrl+Shift+G | `:on-git` | git status |

### All keybindings

| Keybinding | Source | Action |
|------------|--------|--------|
| Alt+P | projects extension | Open project picker |
| Ctrl+Shift+N | TUI callback | Toggle desktop notifications |
| Ctrl+Shift+G | TUI callback | Open git status |

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

;; TUI bridge (for extensions that show menus / insert text)
(ext/set-completion-handler! (fn [opts] ...))  ;; called by TUI at startup
(ext/set-insert-text-handler! (fn [text] ...)) ;; called by TUI at startup
(ext/show-completion! {:items [...] :prompt "" :on-select fn})  ;; show menu
(ext/insert-text! "text")                      ;; insert into editor

;; Keybindings
(ext/get-keybindings)  ;; [{:key-fn (fn [data]) :handler fn}]

;; Registration
(ext/register-extension! ext-map)
(ext/list-extensions)
(ext/list-commands)
(ext/get-command "name")

;; Tool access
(ext/get-ext-tool-definitions)
(ext/get-ext-tool-registry)
```
