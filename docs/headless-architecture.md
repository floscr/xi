# Headless Architecture

Xi runs headless. The TUI is just one client connected to the runtime. Any client (TUI, HTTP/WebSocket, pipe, tests) can connect to the same runtime.

## Architecture

```
+------------------------------------------------------+
|                      Runtime                          |
|                                                       |
|  +-----------+  +-----------+  +------------------+  |
|  | Agent Core|  |  Session  |  |   Extensions     |  |
|  | loop.cljs |  |session.cljs|  |   ext/core.cljs  |  |
|  |provider   |  +-----------+  +------------------+  |
|  +-----+-----+                                        |
|        | callbacks                                    |
|  +-----+------------------------------------------+  |
|  |         Event Bus + Command Dispatch            |  |
|  |  emit!  subscribe!  dispatch!                   |  |
|  +-----+------------------------------------------+  |
+--------+---------------------------------------------+
         | events down, commands up
         |
    +----+-------------+
    |    |              |
+---+--+ +------+ +----+----+
| TUI  | |HTTP/ | |Headless |
|Client| |  WS  | | (pipe)  |
+------+ +------+ +---------+
```

## Runtime (runtime.cljs)

The headless core. Owns agent lifecycle, session management, extension registration, event bus, and command dispatch.

```clojure
(def rt (runtime/create! {:model "claude-sonnet-4-..." :cwd "/path"}))

(runtime/subscribe! rt :text-delta (fn [event] ...))
(runtime/dispatch! rt {:type :prompt :text "hello"})
(runtime/dispatch! rt {:type :abort})
```

## Events (Runtime -> Clients)

Structured maps with a :type keyword, emitted during agent operation.

**Agent events:**
```clojure
{:type :turn-start}
{:type :text-delta    :text "partial..."}
{:type :thinking      :text "..."}
{:type :tool-start    :id "toolu_abc" :name "bash" :arguments {:command "ls"}}
{:type :tool-result   :id "toolu_abc" :content [...] :is-error false}
{:type :turn-end      :session-id "..." :usage {...} :cost 0.03}
;; Note: session name is available via ext/hook-state, not on the event
{:type :error         :error {:type "rate_limit" ...}}
{:type :aborted}
```

**State events:**
```clojure
{:type :ready         :model "..." :cwd "..." :extensions [...]}
{:type :busy-changed  :busy true}
{:type :session-cleared}
{:type :session-resumed  :session {...} :messages [...]}
```

**Command result events:**
```clojure
{:type :command-result :command "help" ...}
{:type :command-error  :command "resume" :text "Session not found."}
```

## Commands (Clients -> Runtime)

```clojure
{:type :prompt  :text "refactor the auth module"}
{:type :abort}
{:type :command :name "clear"}
{:type :command :name "resume" :args "3"}
{:type :quit}
```

Or pass a raw string -- the runtime parses slash commands automatically:
```clojure
(runtime/dispatch! rt "hello world")     ;; -> prompt
(runtime/dispatch! rt "/clear")          ;; -> command
```

## Client Protocol

```clojure
{:on-event      (fn [event] ...)      ;; required
 :on-connect    (fn [runtime] ...)    ;; optional
 :on-disconnect (fn [] ...)}          ;; optional
```

Connect/disconnect:
```clojure
(runtime/connect! rt client)
(runtime/disconnect! rt client)
```

## Source Layout

```
src/xi/
  runtime.cljs            -- headless core (event bus, commands, lifecycle)
  runtime/
    events.cljs           -- event bus (pub/sub)
    commands.cljs         -- command parsing & dispatch
  cli.cljs                -- entry point: create runtime + connect TUI client
  client/
    tui.cljs              -- TUI client (event -> component mutations)
  loop.cljs               -- agent loop (wraps provider)
  provider.cljs           -- Claude Agent SDK integration
  session.cljs            -- session persistence
  tools/                  -- tool definitions and execution
  ext/                    -- extensions (hooks, commands)
  tui/                    -- rendering primitives (ansi, components, editor)
```

## Entry Points

```clojure
;; Interactive TUI (current default)
(let [rt (runtime/create! {})
      client (tui-client/create!)]
  (runtime/connect! rt client))

;; Headless one-shot (future)
(let [rt (runtime/create! {:model model :cwd cwd})
      client (headless/create! {:on-done #(js/process.exit 0)})]
  (runtime/connect! rt client)
  (runtime/dispatch! rt {:type :prompt :text "do the thing"}))

;; WebSocket server (future)
(let [rt (runtime/create! {:model model :cwd cwd})]
  (start-ws-server! rt {:port 3000}))
```
