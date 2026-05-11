# Command Registry

Xi uses a centralized command registry (`xi.command-registry`) as the single source of truth for all slash commands. The palette (ctrl+p), `/help`, and command dispatch all read from the same registry.

## Architecture

```
                    xi.command-registry
                    ┌─────────────────────┐
                    │  name → {           │
                    │    :runtime <cmd>   │
                    │    :client  <cmd>   │
                    │  }                  │
                    └────────┬────────────┘
                             │
              ┌──────────────┼──────────────┐
              ↓              ↓              ↓
     runtime/commands   tui/palette    client/tui
     (scope :runtime)   (reads all)    (scope :client)
```

## Command Shape

Every command is a map:

```clojure
{:name        "model"           ;; unique identifier (part after /)
 :description "Show or set model"  ;; shown in palette & /help
 :handler     (fn [ctx] ...)    ;; execution function
 :scope       :runtime          ;; :runtime | :client
 :show-busy   false             ;; available when agent is busy?
 :hidden      false             ;; hide from /help & palette
 :source      nil}              ;; "ext" for extension commands
```

## Scopes

A command name can have entries in both scopes:

- **`:runtime`** — Runs in the headless runtime. Handlers receive `{:args :sess :cwd :model :effort :busy :event-history}` and return a vector of events.
- **`:client`** — Runs in the TUI client only, never sent to runtime. Handlers receive `{:args}` and have access to TUI state via closure.

When both scopes exist for a name, the TUI tries `:client` first. If the client handler returns `:pass-through`, the command falls through to runtime dispatch.

## Lifecycle

1. **Built-in commands** register at startup via `commands/register-builtin-commands!`
2. **Extensions** register via `ext/register-extension!` → commands auto-feed into the central registry
3. **Client commands** register when the TUI client is created (e.g. `/git`, `/buffers`)
4. **Palette** calls `registry/list-commands` to build its item list

## Registering Commands

### Built-in (runtime)

In `runtime/commands.cljs`:

```clojure
(registry/register!
 {:name "clear"
  :description "Clear current session"
  :handler (fn [{:keys [sess cwd]}]
             (reset! sess (session/create-session cwd))
             (provider/clear-session!)
             [{:type :session-cleared}])
  :scope :runtime})
```

### Extension

In your extension definition:

```clojure
(def extension
  {:name "my-ext"
   :commands [{:name "mycommand"
               :description "Does something"
               :handler (fn [{:keys [session model cwd args]}]
                          ;; Return nil, or {:type :prompt :text "..."} to dispatch a prompt
                          nil)}]})
```

Extension commands are automatically wrapped and registered into the central registry when `ext/register-extension!` is called. They appear in the palette and `/help` without any extra wiring.

### Client (TUI-only)

In `client/tui.cljs`:

```clojure
(cmd-registry/register!
 {:name "git"
  :description "Open ngit"
  :scope :client
  :show-busy true
  :handler (fn [_ctx] (open-git!) nil)})
```

## Pass-through Pattern

When a command needs different behavior depending on arguments:

```clojure
{:name "model"
 :scope :client
 :handler (fn [{:keys [args]}]
            (if (some? args)
              :pass-through          ;; decline → falls through to runtime
              (do (show-picker!)     ;; handle locally
                  nil)))}
```

The runtime has its own `:scope :runtime` entry for `/model` that handles the `set`/`get` logic.

## API

```clojure
(require '[xi.command-registry :as registry])

;; Register
(registry/register! cmd-map)
(registry/register-many! [cmd-maps])

;; Lookup
(registry/get-command "model")            ;; prefers :client, falls back to :runtime
(registry/get-command "model" :runtime)   ;; scope-specific lookup

;; List (for palette, /help)
(registry/list-commands)                        ;; all non-hidden
(registry/list-commands {:scope :runtime})      ;; runtime only
(registry/list-commands {:include-hidden true}) ;; include hidden
```
