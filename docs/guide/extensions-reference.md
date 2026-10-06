# Extension reference

Every key an extension map may hold, every function of the `xi.api`
namespaces, and the limits of the sandbox. Read the [tutorials](extensions.md)
first; this page is for looking things up.

## The map

```clojure
(def extension
  {:id               :my-ext                        ; required, unique
   :init             {:room {…} :process {…}}       ; initial state
   :handlers         {event-type (fn [state event] → {:state … :effects …} | nil)}
   :fx               {effect-type (fn [ctx payload])}
   :commands         [{:name "x" :description "…" :handler (fn [state {:keys [room-id args]}])}]
   :tool-definitions [{:name "x" :description "…" :input_schema {…}}]
   :tool-registry    {"x" (fn [args ctx] → result | promise)}
   :system-prompt    "text" | (fn [cwd] → text | nil)
   :keybindings      [{:key "ctrl+shift+x" :event {:type :ext.my-ext/do}}]
   :prompt-badge     (fn [state] → string | nil)
   :mcp-servers      {:name {:command "…" :args […]}}
   :on-mount         (fn [ctx])
   :on-unmount       (fn [ctx])
   :on-enable        (fn [])
   :on-disable       (fn [])
   :on-shutdown      (fn [])})
```

Any other key rejects the file. In particular `:tool-gate`, `:event-hooks`
and `:remove-tools` are not available: whether a tool call runs is the
[rules'](rules.md) decision, not an extension's.

| Key | Notes |
| --- | --- |
| `:id` | A keyword. Also the name used in rules (`:extension "my-ext"`) and the data directory. |
| `:init` | `:room` is per chat at `[:rooms <id> :ext <ext-id>]`, mirrored to clients. `:process` is one value at `[:ext <ext-id>]`, server only. Existing values win on reload. |
| `:handlers` | Run after Xi's own handler for the same event. May change only the extension's own state; other changes are dropped. |
| `:fx` | Receive `{:dispatch! :get-state …}` as `ctx`. The place for I/O. |
| `:commands` | `:args` is the text after the command name. Handlers are pure; return effects for I/O. |
| `:tool-registry` | `args` has keyword keys. `ctx` holds `:room-id`, `:cwd`, `:dispatch!`, `:get-state`, `:confirm!`. |
| `:system-prompt` | A function gets the chat's directory and may return nil to add nothing. |
| `:keybindings` | Terminal client only, read at startup. Keys like `"alt+r"`, `"ctrl+shift+n"`. `:when (fn [state])` makes it conditional. |
| `:mcp-servers` | Private servers; see [the tutorial](extension-tutorial-mcp.md). |
| `:on-mount` / `:on-unmount` | Load and unload, including every reload. `ctx` has `:dispatch!` and `:get-state`. |
| `:on-enable` / `:on-disable` | `/ext enable` and `/ext disable`. |

## Events and effects

- An extension's own events and effects are named `:ext.<id>/…`. A handler
  may dispatch those, plus `:ui/status` (text in the status line) and
  `:theme/set` (`:light`, `:dark` or nil for the terminal's code blocks).
- `:prompt/submit`, `:chat/start` and `:subagent/spawn` may be dispatched from
  commands, keybindings, effects a command started, and handlers of own
  events a client sent (a click in a page). Never from tools.
- Effects are returned from handlers as `{:effects [[:ext.<id>/name payload] …]}`;
  `[:app/dispatch event]` is the one built-in effect, and dispatches the event.

Events of Xi's that are useful to react to:

| Event | Carries | When |
| --- | --- | --- |
| `:agent/turn-end` | `:room-id` `:aborted?` | A turn finished |
| `:ui/dialog-open` | `:room-id` `:dialog` | A dialog opened; `(:type dialog)` is `:confirm` for a permission request |
| `:route/navigate` | `:page` … | The web client changed page (web half taps) |

## `xi.api.*`

Every function takes `ctx` first and returns a promise, except where noted.
Each call through `fs`, `sh`, `http` and `mcp` is a rules request tagged with
the extension's id; with nobody to answer an ask, it is refused.

### `xi.api.fs`

| Function | Does | Rules request |
| --- | --- | --- |
| `(read ctx path)` | File content as a string | `{:tool :read :path …}` |
| `(write ctx path text)` | Write a file | `{:tool :write …}` |
| `(list ctx dir)` | Entry names | `{:tool :ls …}` |
| `(exists? ctx path)` | Boolean | `{:tool :read …}` |
| `(data-dir ctx)` | The extension's directory; no promise | none |

Relative paths resolve against the chat's directory inside a tool call, and
against the data directory elsewhere. `~` works.

### `xi.api.sh`

`(sh ctx "program" "arg" …)` → stdout on exit 0, rejects otherwise. An
options map before the program: `(sh ctx {:dir "sub"} "bb" "test")`.
Request: `{:tool :sh :cli "program" :command "…"}`. Processes still running
when the extension unloads are stopped.

### `xi.api.http`

`(fetch ctx url {:method :headers :body :timeout-ms})` →
`{:status :ok? :url :headers :body}`. Only `http(s)`; redirects are followed
and `:url` is the final one. Request: `{:tool :net :host …}`.
`url-encode` and `url-decode` are plain functions.

### `xi.api.mcp`

`(call ctx server tool args)` → `{:content … :is-error …}`. `server` is a
key from the extension's `:mcp-servers`, or the id of a server in `mcp.edn`.
Request: `{:tool :mcp :mcp-server … :mcp-tool …}`; a trusted server runs,
an untrusted one asks.

### `xi.api.dialog`

Asks the user in a chat; needs `{:room-id …}` as the last argument outside a
tool call. Not a rules request.

| Function | Resolves to |
| --- | --- |
| `(confirm ctx "Delete it?")` | `true` / `false` |
| `(select ctx "Which?" [{:label "A" :value 1} …])` | the value, nil when dismissed |
| `(alert ctx "Done.")` | nil |
| `(form ctx "Title" [{:name "msg" :label "Message"}])` | `{"msg" "…"}`, nil on cancel |

The text is shown prefixed with the extension's id. With no client attached,
every dialog resolves to its safe default at once.

### `xi.api.json` and `xi.api.promise`

`json/parse` (keyword keys; `{:keywordize? false}` for strings),
`json/stringify`, `json/pretty`. `p/then`, `p/catch`, `p/all`, `p/resolve`,
`p/reject`, `p/delay`. `.then` interop is not available in the sandbox.

## Rules for extensions

Defaults: the data directory is allowed; credential paths are denied; every
program, host and untrusted MCP server asks. Pre-allow in
`~/.config/xi/rules.edn`, pinned to the extension:

```clojure
{:match {:tool :sh  :extension "ping" :cli "notify-send"} :action {:type :allow}}
{:match {:tool :net :extension "hn"   :host "hn.algolia.com"} :action {:type :allow}}
{:match {:tool :read :extension "theme" :path "~/.local/state/theme"} :action {:type :allow}}
```

An `a` answer in a dialog adds the same rule for the chat. It never extends
to the agent or to another extension.

## The sandbox

Available: `clojure.core`, `clojure.string`, `clojure.set`, `clojure.walk`,
`clojure.edn`, `xi.core.state` (pure helpers: `active-room`, `get-room`,
`room-ext`, `mode`, `port`), `xi.core.events`, and `xi.api.*`.

Not available: `js/` interop, `node:*` and npm requires, `aget`, `eval`,
`resolve`, class constructors, environment variables, `.then`.

A sibling namespace is loaded from the extension's directory:
`(:require [notes.util])` reads `notes/util.cljs`.

## Browser halves

`<name>/web.cljs` defines `web-extension` with the same `:id` and only these
keys: `:routes`, `:pages`, `:nav-items`, `:taps`.

| Key | Shape |
| --- | --- |
| `:routes` | `{"segment" {:parse (fn [segments] → {:page kw …}) :path {page-kw (fn [route] → "/url")}}}` |
| `:pages` | `{page-kw (fn [state dispatch!] → hiccup)}`; page keywords are namespaced with the id |
| `:nav-items` | `[{:menu :sidebar/:palette/:home-topbar/:overflow :label "…" :icon :kw :event {…}}]`; overflow items may set `:mode :room` or `:project` |
| `:taps` | `[(fn [dispatch!] → (fn [event state]))]` |

Pages may use `clojure.*`, `xi.core.state`, `ui.*` components (minus the
ones that touch `js/window`), `xi.web.views` helpers (`nav-group`,
`overflow-menu`, `spinner`, `shorten-path`, `diff-rows-view`), `xi.diff`
(`parse-diff-text`, `diff-rows`) and `xi.markdown.hiccup/render`.

`dispatch!` sends `:ext.<id>/*` events to the server (tagged with the active
chat and the client), passes `:route/navigate` and `:nav/back`, and drops
everything else. Browser-only state lives at `[:user-ext/ui <id> …]`: an
input with `:bind [:k]` (in `:attrs` for `ui.form` inputs) keeps its value
there, and `{:type :ext-ui/set :path [:k] :value v}` writes it. Output is
sanitised: no script-capable tags, no string event handlers, no
`javascript:` URLs.

## Reloading

`/ext reload` and the `ext_reload` tool re-evaluate every enabled file:
`:on-unmount` runs, the old code loses its API access, the new code is
registered, missing `:init` keys are seeded, `:on-mount` runs. Handlers,
commands, effects and the system prompt apply at once; tools from the next
turn; keybindings and web halves need a restart or a page reload. A file
that fails to evaluate is reported and its old version unloaded.

## Limits

- A loop that never yields blocks the server. Code cannot be interrupted.
- Each process reads its own extensions directory. A terminal joined to a
  server elsewhere presents the commands of *its* directory and runs their
  effects on the server, from the server's directory; keep the two in sync.
- A web half loads once per page load.
- System-prompt text steers the agent, but the agent's tool calls still go
  through the rules.

The full reference, including the guard rules and the loader, is in
[user extensions](../user-extensions.md); the built-in extension surface
(which includes keys user extensions cannot use) is in
[extensions](../extensions.md).
