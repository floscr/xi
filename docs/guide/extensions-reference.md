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
| `:keybindings` | Terminal client only, read at startup. Each entry is a keyboard action: `:key` is its default key (`"alt+r"`, `"ctrl+shift+n"`, any spelling from [Keyboard shortcuts](keyboard.md#writing-a-key)), which the user may change under `:keys` in `config.edn`; `:id` names the action (default: the event's `:type`), `:label` is its name in the shortcut list, `:layer` where the key applies (default `:global`; e.g. `:buffer/diff`). `:when (fn [state])` makes it conditional. |
| `:mcp-servers` | Private servers; see [the tutorial](extension-tutorial-mcp.md). |
| `:on-mount` / `:on-unmount` | Load and unload, including every reload. `ctx` has `:dispatch!` and `:get-state`. |
| `:on-enable` / `:on-disable` | `/ext enable` and `/ext disable`. |

## Events and effects

- An extension's own events and effects are named `:ext.<id>/…`. A handler
  may dispatch those, plus `:ui/status` (text in the status line) and
  `:theme/set` (`:light`, `:dark` or nil for the terminal's code blocks).
- `:prompt/submit`, `:chat/start`, `:subagent/spawn` and `:session/resume`
  (`{:room-id … :session-id …}`: load a saved session into the chat, as
  `/resume id:<id>` does) may be dispatched from commands, keybindings,
  effects a command started, and handlers of own events a client sent (a
  click in a page). Never from tools.
- An own event a client sends needs no chat: it also arrives from the home
  view and the sidebar, with `:user` and no `:room-id`.
- Effects are returned from handlers as `{:effects [[:ext.<id>/name payload] …]}`;
  `[:app/dispatch event]` is the one built-in effect, and dispatches the event.
- An own event dispatched with `:to-users #{"alice" …}` is also sent to every
  connected device of those users, one with `:to-client <client-id>` to that
  one client (the id a client's event arrived with). In the browser the
  extension's [web half](#browser-halves) reduces it into its own slice with a
  `:handlers` entry. Only the server half can address an event this way: the
  two keys are dropped from anything a client sends.

Events of Xi's that are useful to react to:

| Event | Carries | When |
| --- | --- | --- |
| `:agent/turn-end` | `:room-id` `:aborted?` | A turn finished |
| `:ui/dialog-open` | `:room-id` `:dialog` | A dialog opened; `(:type dialog)` is `:confirm` for a permission request |
| `:prompt/submit` | `:room-id` `:text` `:user` | Someone sent a prompt |
| `:room/presence` | `:room-id` `:members` | Who is in a room changed; `:members` is client id to `{:user :platform}` |
| `:route/navigate` | `:page` … | The web client changed page (web half taps) |

### Users and their state

On a shared server every event a client sends carries `:user`, the sender's
user id (`root` by default; see [Users](server.md#users)). A command's handler
is given it too, as `:user` next to `:room-id` and `:args`. In state,
`[:connection :user]` is the id this process acts as, a room's `:members`
lists who is attached, and a `:user` history entry carries its sender under
`:user`. Xi only tells users apart; authentication is yours to add. What a
user's chats may do is a matter of [rules](rules.md#rules-for-some-users)
(`:user`).

Each user the server has seen has a record at `[:users <id>]`:

```clojure
{:id "alice"
 :name "Alice"                 ; from config.edn :users, or nil
 :meta {:team "ops"}           ; from config.edn :users, read-only
 :ui   {:theme "dark" …}       ; their web client choices
 :ext  {:my-ext {…}}}          ; what each extension keeps about them
```

Read it with plain state access: `(xi.core.state/user-record st "alice")`,
`(xi.core.state/user-ext st "alice" :my-ext)`. The record is server-side only
and never goes to a browser or terminal. A handler cannot change it: like the
rest of the state outside the extension's own slices, a change to `:users` is
dropped. The data an extension keeps is changed through `xi.api.user`.

### `xi.api.user`

Who is acting, and a place for the extension to keep data per user. These
functions are synchronous and are not rules requests: the state is the
extension's own, like its data directory.

| Function | Does |
| --- | --- |
| `(current ctx)` | The user id this call acts for. In a tool call, the user whose prompt started the turn; elsewhere the user the server itself acts as. |
| `(info ctx)` `(info ctx id)` | `{:id :name :meta :ui}`, read-only. Holds nothing of any extension's data. |
| `(users ctx)` | `[{:id :name} …]`: every user the server knows. |
| `(state ctx)` `(state ctx id)` | What this extension keeps for the user, nil when nothing. |
| `(set-state! ctx value)` `(set-state! ctx id value)` | Keep `value` for the user and persist it. nil forgets it. Returns `value`. |

```clojure
(ns visits (:require [xi.api.user :as user]))

(def extension
  {:id :visits
   :tool-registry
   {"visit" (fn [_ ctx]
              (let [n (inc (or (:n (user/state ctx)) 0))]
                (user/set-state! ctx {:n n})
                {:content [{:type "text"
                            :text (str (:name (user/info ctx)) ": visit " n)}]}))}})
```

What the extension can touch is fixed by who it is. It reads and writes only
its own entry; another extension's data is not reachable, and `:name`, `:meta`
and `:ui` cannot be changed from here. A value has to be plain data (nil,
booleans, numbers, strings, keywords, and vectors, lists, sets and maps of
those) under 64 KB printed, or the call throws and nothing is written. Passing
an `id` that is not a plain user id throws too.

#### Session flags

An extension that keeps `{:session-flags {:favorite? ["<session-id>" …]}}` in
its user state makes the server tag each of that user's sessions with
`:favorite? true|false` (and the live rooms too): that is how a web half knows
which sessions are starred. A flag is a keyword ending in `?`; the lobby's own
(`:dismissed? :active? :busy? :unread? :current? :has-dialog? :error?`) are
reserved. Writing the state refreshes every device of the user. The favorites
extension in the author's dotfiles is the worked example.

A handler or an effect has no `ctx` with a user in it. Take the id from the
event that started the work, a command's `:user` or a client event's `:user`,
and pass it explicitly: `(user/state ctx (:user payload))`.

Writes go to `~/.config/xi/state/users/<id>.edn` and update `[:users <id> :ext]`
at once, so they survive a restart and handlers see them straight away.

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

### `xi.api.sessions`

Synchronous and read-only, like `xi.api.user`; not a rules request. It exposes
four fields of saved-session metadata, never a conversation.

| Function | Does |
| --- | --- |
| `(summaries ctx ids)` | `[{:session-id :name :cwd :last-accessed} …]` for the ids that name a saved session, in the order given; unknown ids are left out. |

An extension that keeps session ids shows them by name with it, and opens one
by dispatching `:session/resume` from a command.

### `xi.api.dialog`

Asks the user in a chat; needs `{:room-id …}` as the last argument outside a
tool call. Not a rules request.

| Function | Resolves to |
| --- | --- |
| `(confirm ctx "Delete it?")` | `true` / `false` |
| `(select ctx "Which?" [{:label "A" :value 1} …])` | the value, nil when dismissed |
| `(alert ctx "Done.")` | nil |
| `(form ctx "Title" [{:name "msg" :label "Message"}])` | `{"msg" "…"}`, nil on cancel |

The text is shown prefixed with the extension's id. A dialog waits for an
answer even while no client is connected, and shows when one joins; only in
`xi prompt` mode does it resolve to its safe default at once.

### `xi.api.time`

`(now)` → milliseconds since the epoch. The sandbox has no `js/Date`; this is
the clock, on the server and in a web half alike. Not a rules request.

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
`room-ext`, `mode`, `port`, `own-user`, `user-record`, `user-ext`),
`xi.core.events`, and `xi.api.*`.

Not available: `js/` interop, `node:*` and npm requires, `aget`, `eval`,
`resolve`, class constructors, environment variables, `.then`.

A sibling namespace is loaded from the extension's directory:
`(:require [notes.util])` reads `notes/util.cljs`.

## Browser halves

`<name>/web.cljs` defines `web-extension` with the same `:id` and only these
keys: `:routes`, `:pages`, `:nav-items`, `:palette-items`, `:taps`, `:tool-views`,
`:sidebar-groups`, `:session-menu-items`, `:handlers`.

| Key | Shape |
| --- | --- |
| `:routes` | `{"segment" {:parse (fn [segments] → {:page kw …}) :path {page-kw (fn [route] → "/url")}}}`. A route may carry a `:params` map (`{:page :chat/thread :params {:conv "c1"}}`): it stays on the route as is, so `:path` and the page read it back from `[:web/route :params]`. |
| `:pages` | `{page-kw (fn [state dispatch!] → hiccup)}`; page keywords are namespaced with the id |
| `:nav-items` | `[{:menu :sidebar/:palette/:home-topbar/:overflow :label "…" :icon :kw :event {…}}]`; overflow items may set `:mode :room` or `:project`. A sidebar item may set `:badge-path [:user-ext/state <id> …]`: the positive number at that path of the extension's browser slice shows as a badge (an unread count). |
| `:palette-items` | `(fn [state] → [{:label "…" :icon :kw :event {…}}])`: entries computed from the app state on every render and listed in the Cmd+K palette's Navigate group (e.g. one row per account stored in the extension's room slice). Events follow the `:nav-items` rule; a throw yields no entries. |
| `:taps` | `[(fn [dispatch!] → (fn [event state]))]` |
| `:handlers` | `{:ext.<id>/event (fn [slice event] → slice')}`: pure reducers over the extension's browser slice at `[:user-ext/state <id>]`, run when the server half [pushes](#events-and-effects) that event to this user. They see nothing but the slice and get no `dispatch!`; a throw or a non-map result keeps the slice. |
| `:tool-views` | `{"tool_name" (fn [call slice] → hiccup or nil)}`; only the extension's own tools |
| `:sidebar-groups` | `[{:id :<ext>/group :label "…" :where :flag? :limit 5 :more {:label "…" :icon :kw :event {…}}}]`: a drawer group (between Drafts and Recent) of the sessions whose `:where` flag is true, most recent first; `:more` is a closing row shown when there are more than `:limit`. The `:id` is namespaced with the extension's id. |
| `:session-menu-items` | `[{:label "…" :label-on "…" :flag :flag? :icon :kw :event {…}}]`: entries of every session's context menu and the palette's "Current session" group. The `:event` gets the session's `:session-id`; `:label-on` replaces `:label` while the session's `:flag` is true. |

A tool view replaces the text result in the chat's block for one of the
extension's own tools. `call` is the finished call, `{:tool :arguments :text
:is-error}`, with argument keys as keywords. `slice` is the extension's room
slice, so a view can show data the server half keeps there; a tool fn can put
it there by dispatching its own `:ext.<id>/*` event. nil, or a view that
throws, shows the text result instead.

Pages may use `clojure.*`, `xi.core.state`, `ui.*` components (minus the
ones that touch `js/window`), `xi.web.views` helpers (`nav-group`,
`overflow-menu`, `spinner`, `shorten-path`, `diff-rows-view`, `user-avatar`,
`avatar-stack`), `xi.diff` (`parse-diff-text`, `diff-rows`),
`xi.markdown.hiccup/render` and `xi.api.time/now`.

`dispatch!` sends `:ext.<id>/*` events to the server (tagged with the active
chat and the client), passes `:route/navigate` and `:nav/back`, and drops
everything else. The events of `:nav-items`, `:sidebar-groups` and
`:session-menu-items` follow the same rule: an own event is sent to the
server, navigation passes, anything else is dropped. Browser-only state lives at `[:user-ext/ui <id> …]`: an
input with `:bind [:k]` (in `:attrs` for `ui.form` inputs) keeps its value
there, and `{:type :ext-ui/set :path [:k] :value v}` writes it. A bound field
may add `:on-enter {:type :ext.<id>/send …}`: Enter (not Shift+Enter) sends
that event to the server with the field's text as `:text` and clears the
field — a page cannot read key events itself. What the server half pushed to
this user lives at `[:user-ext/state <id>]` (see `:handlers`). Output is
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

Xi's own built-in extensions use a larger surface (event hooks, tool
replacement, server routing) that is compiled into Xi; it is described for
contributors in [`docs/extensions.md`](../extensions.md) in the repository.
