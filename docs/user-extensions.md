# User extensions

Put a `.cljs` file into `~/.config/xi/extensions/`, list its name under
`:extensions` in `~/.config/xi/config.edn`, and an already-built xi loads it at
startup. No build step is needed. User extensions have the same
shape as built-in ones ([extensions.md](extensions.md)) but run in a
**capability sandbox**. They get no host access of their own, and every side
effect goes through the rules engine.

## A minimal extension

```clojure
;; ~/.config/xi/extensions/notes.cljs
(ns notes
  (:require [xi.api.fs :as fs]
            [xi.api.promise :as p]))

(defn- notes-file [ctx] (str (fs/data-dir ctx) "/notes.md"))

(def extension
  {:id :notes
   :tool-definitions
   [{:name "notes_add"
     :description "Append a line to my notes file."
     :input_schema {:type "object"
                    :properties {:text {:type "string"}}
                    :required ["text"]}}]
   :tool-registry
   {"notes_add"
    (fn [{:keys [text]} ctx]
      (-> (fs/read ctx (notes-file ctx))
          (p/catch (fn [_] ""))
          (p/then #(fs/write ctx (notes-file ctx) (str % text "\n")))
          (p/then (fn [_] {:content [{:type "text" :text "added"}]}))))}
   :commands
   [{:name "notes"
     :description "Show my notes"
     :handler (fn [_st {:keys [room-id]}]
                {:effects [[:ext.notes/show {:room-id room-id}]]})}]
   :fx
   {:ext.notes/show
    (fn [{:keys [dispatch!] :as ctx} {:keys [room-id]}]
      (-> (fs/read ctx (notes-file ctx))
          (p/then #(dispatch! {:type :ui/status :room-id room-id :text %}))))}})
```

The notes file lives in the extension's data dir,
`~/.local/share/xi/extensions/notes/`, which the extension may read and write
freely. Relative paths resolve against the room's cwd inside a tool call (the
project, where the normal write rules apply), and against the data dir
when there's no room cwd (e.g. in an fx).

## Enabling

A file in the directory is loaded only when the user config file names it:

```clojure
;; ~/.config/xi/config.edn
{:type :xi/config
 :version 1
 :extensions ["notes.cljs"]}
```

- `:extensions` is a vector of top-level file names. A file that isn't listed
  is never read or evaluated. It is logged at startup
  (`[user-ext] not enabled (…): foo.cljs`) and reported by `/ext reload`.
- With no config file, no `:extensions` key, or an invalid config file
  (see [config.md](config.md#user-config-configxiconfigedn)), nothing
  loads.
- The list lives in `config.edn` because agents can't change it: writes under
  `~/.config/xi` are hard-blocked (see [rules.md](rules.md)). Dropping a file
  into the directory is therefore not enough to get code loaded, and a
  checked-out project can't enable anything.
- The list names files, not contents. Editing a file that is already enabled
  is an ordinary write under the normal write rules.
- An **agent profile** (`xi … --agent ID`, see [cli.md](cli.md#agent-profiles))
  may carry its own `:extensions` vector under `[:agents ID]`; it replaces
  the top-level list for that process, so a restricted agent loads only what
  it needs.

## Files

- Each enabled **top-level** `*.cljs` file in `~/.config/xi/extensions/` is one
  extension, loaded alphabetically after the built-ins and MCP servers. A
  symlink to a file counts, so an extension can live in another checkout
  (link its sibling directory too).
- A file defines `extension`: the extension map for the server side (tools,
  handlers, commands, fx, system prompt, …).
- A file may require its own sibling namespaces: `(:require [notes.util])` loads
  `~/.config/xi/extensions/notes/util.cljs`.
- `<name>/web.cljs` defining `web-extension` is the extension's optional
  **browser half** (see [Browser halves](#browser-halves)).

`/ext list` shows loaded extensions. `/ext reload` re-reads the enabled list
and the directory. Agents can do the same with the `ext_reload` tool (no
arguments): call it after editing an extension file; a file that fails to
evaluate comes back as an error result with the reason. Rejected files are
logged with the reason (`[user-ext] rejected foo.cljs: …`) and reported by
`/ext reload`.

### Live reload: mount and unmount

A reload needs no server restart. Every enabled file is re-evaluated in a
fresh sandbox (its sibling namespaces too), and the extension is swapped in
while the server runs:

1. **Unmount** the old code: its `:on-unmount` runs, everything it still owns
   is released, and its capability token is revoked.
2. **Register** the new code, so handlers, commands and fx are the new ones
   from the next event on.
3. **Mount** the new code: `:init` keys the live room slices lack are seeded
   (existing values win), then `:on-mount` runs.

```clojure
(def extension
  {:id :notes
   :init {:room {:items []}}
   ;; ctx: {:dispatch! … :get-state …}, the extension's own capability
   :on-mount   (fn [{:keys [dispatch!]}] (dispatch! {:type :ext.notes/load}))
   :on-unmount (fn [_ctx] …)})
```

- **State survives.** Room and process slices (`[:ext <id>]`) are not reset.
  A new `:init` key appears, a removed one stays until you clear it.
- **Auto-unmounted:** child processes started with `xi.api.sh` are killed
  and the extension's headless browser is closed. After unmount the old
  code's `xi.api.*` calls are refused and its `dispatch!` goes nowhere, so a
  timer or in-flight promise from the previous version can't act on the new
  one. Write `:on-unmount` only for what the extension holds itself (it
  cannot hold host resources: there is no `js/`).
- **A hook that throws** is logged and ignored; the reload carries on.
- **A file that no longer evaluates** is rejected and its old version is
  unmounted, so nothing half-old keeps running. Fix the file and reload again.
- **Live where the app reads the composition late:** the server and
  standalone TUI. Handlers, commands, fx and the system prompt follow at once;
  tool definitions apply from the next turn. Keybindings and a TUI client's
  command-completion list are fixed at startup, and a browser half applies on
  the next page load.
- An in-flight fx call of the old code keeps running until it finishes
  (SCI can't be interrupted); it just can no longer call `xi.api.*` or
  dispatch.

## The sandbox

User code runs in SCI with the hardened setup shared with the clj tool
(`xi.sandbox.sci`):

- **No host access.** There's no `js/` interop, no `node:*`, no npm
  requires, no `aget`, `js-obj`, `eval`, `resolve` or `intern`, and no class
  constructors.
- **Available:** `clojure.core`, `clojure.string` / `set` / `walk` / `edn`,
  `xi.core.state` and `xi.core.events` (pure helpers), and the capabilities
  below.

### Capabilities (`xi.api.*`)

Every call takes the `ctx` your fn was given as its first argument and returns
a Promise. Each call is decided by the **rules engine** like an agent tool
call, with the request tagged `:extension <id>`. If a rule asks and there is
no one to answer (no room, no client), the call is refused.

The ctx *is* the capability. The loader stamps it with an opaque
per-extension token (`:xi.api/token`), and `xi.api.*` resolves the caller
from that token, not from `:extension`. A ctx that names another extension's
id, or one you build yourself, is refused — so no extension can borrow
another's grants or declared hosts, and the sandbox has no way to mint a
token.

| Namespace | Functions | Request |
|---|---|---|
| `xi.api.fs` | `read` `write` `list` `exists?` `data-dir` | `{:tool :read/:write/:ls :path …}` |
| `xi.api.sh` | `(sh ctx "cmd" "arg" …)` (optional `{:dir …}` before the argv) | `{:tool :sh :cli :command :argv}` |
| `xi.api.http` | `(fetch ctx url {:method :headers :body :timeout-ms})` (http(s) only) → `{:status :ok? :url :headers :body}`; `url-encode` `url-decode` (pure) | `{:tool :net :host …}` |
| `xi.api.mcp` | `(call ctx server tool args {:room-id})` → the tool result `{:content :is-error}`, from one of the extension's [own MCP servers](#mcp-servers-of-your-own) or a [configured one](mcp-servers.md#calling-servers-from-user-extensions) | `{:tool :mcp :mcp-server :mcp-tool}` |
| `xi.api.dialog` | `confirm` `select` `alert` `form`; see [Dialogs](#dialogs) | (no request) |
| `xi.api.json` | `parse` `stringify` `pretty` | (no request) |
| `xi.api.promise` | `then` `catch` `all` `resolve` `reject` `delay` | (no request) |

`.then` interop is blocked in the sandbox, so chain Promises with
`xi.api.promise`. Relative paths resolve against the room's cwd, or against
the data dir outside a room.

- The gate checks the host of the URL you pass. Redirects are followed and
  `:url` is where the request ended up.
- There is no `js/JSON`: `(json/parse s)` returns Clojure data with keyword
  keys (`{:keywordize? false}` keeps strings), `(json/stringify x)` builds a
  payload, and `(json/pretty s)` re-indents a JSON string without reordering
  its keys.
- There is no env access. Keep keys and tokens in a file in the data dir
  (`(fs/data-dir ctx)`), which the extension may read without asking.

Defaults that apply to extensions (see [rules.md](rules.md)):

- Their own data dir is allowed.
- Credential paths (`~/.ssh`, `~/.gnupg`, `~/.config/xi`, `~/.netrc`, …) are
  denied.
- Every shell command asks. clj's read-only auto-run list doesn't apply,
  because a real process has none of clj's confinement.
- Every network host asks.
- An MCP server asks until it's trusted (once, until its code changes; see
  [below](#mcp-servers-of-your-own)).
- `[a]lways` saves a session rule pinned to that extension (and host or exact
  command). It never extends to the agent or to other extensions.

Pre-allow things permanently in `~/.config/xi/rules.edn`:

```clojure
{:match {:tool :sh :extension "notify" :cli "notify-send"} :action {:type :allow}}
{:match {:tool :net :extension "pushover" :host "api.pushover.net"} :action {:type :allow}}
```

### What an extension can touch in xi itself

The loader wraps every user fn (`xi.ext.user.guard`):

- **State:** handler and command results only change `[:ext <id>]` and
  `[:rooms * :ext <id>]`. Any other change is discarded, including rule state,
  other extensions, room and agent state, and dialogs.
- **Dispatch:** events are limited to the extension's own `:ext.<id>/*`
  events plus `:ui/status` and `:theme/set`
  ([below](#following-the-system-theme)). `:prompt/submit`, `:subagent/spawn`
  and `:chat/start` ([below](#starting-a-chat)) are also allowed from
  **commands and keybindings** (user-initiated), from an `:fx` that a
  command's effect started (so a command can gather data in an effect, then
  submit), and from the handler of an own event **a connected client sent**
  (a click in a browser half; the WS server marks those as the user's). They
  are never allowed from tool fns, or from handlers reached by a tool's own
  events, so an agent can't drive the turn loop, start an unconfirmed
  sub-agent or open chats through an extension tool. This covers `dispatch!`,
  handler effects and keybinding events. Anything else is dropped and logged.
- **Effects:** only the extension's own `:fx` types and the filtered
  `[:app/dispatch …]` get through.
- **Errors:** a throwing handler, fx or command is logged and ignored. A
  throwing tool returns an error result.

- **Sync:** a changed `[:rooms <rid> :ext <id>]` slice is re-emitted as
  `:user-ext/sync`. Clients mirror rooms by replaying the server's reducers,
  but they don't have a user extension's server handlers, so the slice is
  sent to them whole. Room slices therefore reach every client in the room,
  including browser halves. The process-level `[:ext <id>]` stays on the server.

Allowed map keys: `:id :init :handlers :fx :commands :tool-definitions
:tool-registry :system-prompt :keybindings :prompt-badge :on-shutdown
:on-enable :on-disable :on-mount :on-unmount :mcp-servers` (see
[MCP servers of your own](#mcp-servers-of-your-own); `:on-mount` /
`:on-unmount` are the [reload lifecycle](#live-reload-mount-and-unmount)). `:on-enable` /
`:on-disable` still fire for `/ext enable|disable`.
`:tool-gate`, `:event-hooks` and `:remove-tools` are rejected, because policy
belongs to the rules engine. `:id` and tool names
must not clash with anything already loaded, built-ins included.

## Dialogs

`xi.api.dialog` asks the user something in a room and resolves to the answer:

```clojure
(dialog/confirm ctx "Delete the cache?")                      ; → true / false
(dialog/select  ctx "Which tab?" [{:label "Docs" :value 3} …]) ; → 3, nil when dismissed
(dialog/alert   ctx "Done.")                                  ; → nil once dismissed
(dialog/form    ctx "Commit" [{:name "msg" :label "Message"}]) ; → {"msg" …}, nil on cancel
```

- Each takes a trailing opts map with `:room-id`. Tool fns have a room in
  their ctx; an `:fx` gets it from its payload, so pass it.
- The text is prefixed `[extension <id>]`, so the user knows it isn't the
  agent asking.
- Opening a dialog changes nothing by itself, so it isn't a rules request.
  With no client connected at all, it resolves at once to the safe default
  (`false` / `nil`), like every other dialog.
- A `select` option is `{:label :value}` or a plain value (shown with `str`);
  a `form` field takes `:name`, optional `:label` and a prefilled `:value`.

## Starting a chat

`{:type :chat/start :text "…" :cwd "/project" :client-id id}` opens a new
chat whose first user message is `:text`, and the agent starts working on it
at once (a background room, like `POST /api/rooms`). `:cwd` defaults to the
dispatching room's. With `:client-id`, that client is navigated to the new
chat; without it the chat just shows up in the lobby.

The usual use is a button in a browser half. A click arrives at the server
half as the extension's own event, carrying the clicking client's `:client-id`
and the active `:room-id`, and its handler turns it into the chat:

```clojure
;; browser half
(button/button {:on-click (fn [_] (dispatch! {:type :ext.notes/discuss :n 3}))} "Discuss")

;; server half
:handlers
{:ext.notes/discuss
 (fn [_st {:keys [client-id n]}]
   {:effects [[:app/dispatch {:type :chat/start
                              :client-id client-id
                              :text (str "Let's go through note " n)}]]})}
```

Commands and keybindings can dispatch it the same way. Tool fns can't.

## Following the system theme

The TUI paints tool and code blocks in a dark or a light palette. Xi has no
idea what your OS theme is, so an extension tells it by dispatching
`{:type :theme/set :mode :light}` (`:light`, `:dark`, or `nil` to go back to the
default `:dark`). The mode is process-level: it applies to the TUI of the
process that handles the event and repaints at once. The `XI_THEME_MODE` env
var still overrides it (see [config.md](config.md)).

An extension has no file watchers, so a polling loop that reads a state file
and dispatches on change is the simplest way to follow a theme switcher:

```clojure
;; ~/.config/xi/extensions/theme_mode.cljs
(ns theme-mode
  (:require [clojure.string :as str]
            [xi.api.fs :as fs]
            [xi.api.promise :as p]))

(def ^:private state-file "~/.local/state/theme-mode/mode")

(def ^:private running? (atom false))
(def ^:private last-mode (atom ::unset))

(defn- poll! [{:keys [dispatch!] :as ctx}]
  (when @running?
    (-> (fs/read ctx state-file)
        (p/then #(some-> % str/trim str/lower-case))
        (p/catch (fn [_] nil))
        (p/then (fn [mode]
                  (when (not= mode @last-mode)
                    (reset! last-mode mode)
                    ;; no file, or an unknown value → back to the default
                    (dispatch! {:type :theme/set :mode (keyword mode)}))
                  (p/then (p/delay 1000) (fn [_] (poll! ctx))))))))

(def extension
  {:id :theme-mode
   :on-mount   (fn [ctx] (reset! running? true) (poll! ctx))
   ;; a reload evaluates the file afresh, so the old loop's flag is its own
   :on-unmount (fn [_ctx] (reset! running? false))})
```

Reading a file outside the project asks by default, and an unanswered ask in
an effect is refused, so pre-allow it in `rules.edn`:

```clojure
{:match {:tool :read :extension "theme-mode" :path "~/.local/state/theme-mode/mode"}
 :action {:type :allow}}
```

## MCP servers of your own

An extension that needs more than `xi.api.*` offers — a headless browser, a
database, a language server — brings it as an MCP server. `:mcp-servers`
declares them, each in the shape of an `mcp.edn` entry
([mcp-servers.md](mcp-servers.md)):

```clojure
;; ~/.config/xi/extensions/shop.cljs
(ns shop
  (:require [clojure.string :as str]
            [xi.api.http :as http]
            [xi.api.mcp :as mcp]
            [xi.api.promise :as p]))

(defn- search [{:keys [query]} ctx]
  (-> (mcp/call ctx :browser "navigate_page"
                {:type "url" :url (str "https://www.example-shop.com/s?q=" (http/url-encode query))})
      (p/then (fn [_]
                (mcp/call ctx :browser "evaluate_script"
                          {:function "() => [...document.querySelectorAll('h2')].map(h => h.innerText)"})))
      (p/then (fn [res] {:content (:content res)}))))

(def extension
  {:id :shop
   :mcp-servers {:browser {:command "npx"
                           :args ["-y" "chrome-devtools-mcp@1.10.1" "--headless" "--isolated"
                                  "--no-usage-statistics" "--no-page-id-routing"]}}
   :tool-definitions [{:name "shop_search"
                       :description "Search example-shop.com."
                       :input_schema {:type "object"
                                      :properties {:query {:type "string"}}
                                      :required ["query"]}}]
   :tool-registry {"shop_search" search}})
```

- **Private.** A declared server is the extension's own: it isn't offered to
  the agent, and only this extension reaches it, through `xi.api.mcp/call`
  with the server's name (`:browser`). Other extensions can't call it.
- **Trusted once.** Each server is `"<extension>/<name>"` (`"shop/browser"`)
  to the rules engine and to MCP trust. Its first call asks, showing the
  server and the call; `[a]lways` trusts it until its command line or the
  code it runs changes ([trust](mcp-servers.md#trusting-a-server-once-until-its-code-changes)).
  `/mcp trust shop/browser` does it without a call, and `/mcp list` shows
  declared servers with their extension. Calls from an `:fx` can't be asked
  about, so trust the server before using one from an effect.
- **Lifecycle.** Nothing is started until the first call. The server is
  stopped when the extension unmounts (a reload, its file removed) and when
  xi exits. It runs as you, with xi's environment, like any `mcp.edn` server.

A malformed `:mcp-servers` rejects the file: names are simple keywords, each
entry a stdio `:command` (with string `:args`, `:env`, `:cwd`) or
`:transport :http` with an http(s) `:url`.

## Browser halves

An extension with a subdirectory can ship UI for the web client:

```clojure
;; ~/.config/xi/extensions/notes/web.cljs
(ns notes.web
  (:require [ui.button :as button]
            [xi.core.state :as state]))

(defn- notes-page [st dispatch!]
  (let [text (get-in (state/active-room st) [:ext :notes :text])]
    [:div
     [:h2 "Notes"]
     (button/button {:variant :secondary :size :sm
                     :on-click (fn [_] (dispatch! {:type :ext.notes/refresh}))}
                    "Refresh")
     [:pre (or text "(empty)")]]))

(def web-extension
  {:id :notes
   :routes {"notes" {:parse (fn [_] {:page :notes/list})
                    :path  {:notes/list (fn [_] "/notes")}}}
   :pages {:notes/list notes-page}
   :nav-items [{:menu :sidebar :label "Notes" :icon :file-text
                :event {:type :route/navigate :page :notes/list}}]})
```

The server half handles `:ext.notes/refresh`, reads the file through
`xi.api.fs`, and stores the text in the room's `[:ext :notes]` slice, which
syncs back to the page. The full version is the demo extension in
`scripts/demo-extensions/`, seeded by `bb demo`.

How it loads:

- After connecting, the web client asks the server for the web halves
  (`:user-ext/web-sources`). The server replies to that client only, with the
  source of `<ns>.web` and every other file under the extension's
  subdirectory. The browser resolves requires against those files.
- The SCI evaluator is a separate, lazily loaded shadow module (`:user-ext`),
  so clients without web halves never download it.
- Halves are evaluated in the same hardened SCI setup as the server side.
  Available namespaces are `clojure.*`, `xi.core.state`, a few pure
  `xi.web.views` helpers (`nav-group`, `overflow-menu`, `spinner`,
  `shorten-path`, `diff-rows-view`), `xi.diff` (`parse-diff-text`,
  `diff-rows`, which feed `diff-rows-view`), `xi.markdown.hiccup/render`
  (markdown string → hiccup), and the `ui.*` components (without the ones
  that touch `js/window`).

What a browser half can do (`xi.web.user-ext.guard`):

- **Keys:** only `:id :routes :pages :nav-items :taps`. There are no
  `:handlers` or `:fx`, because the logic lives in the server half. `:id` must
  match the server half.
- **Pages** are namespaced by the extension id (`:notes/…`). Route segments
  can't shadow built-in ones (`chat`, `projects`, `git-status`) or another
  extension's routes.
- **dispatch!** sends the extension's own `:ext.<id>/*` events to the server
  (tagged with the active room) and passes `:route/navigate` / `:nav/back`.
  Anything else is dropped. The same applies to nav-item events.
- **UI state (browser-only):** the sandbox can't read a DOM event, so a page
  keeps things like an input's text in per-extension UI state that lives in
  the browser (never sent to the server or other clients, gone on reload). It
  sits at `[:user-ext/ui <id> & path]` of the `state` a page is rendered with;
  a page reads only its own id's slice by convention. Put `:bind [:code]` among
  an input's attributes (for `ui.form/form-input`: `:attrs {:bind [:code]}`)
  and the host sets its `:value` from that path and writes every keystroke
  back. A page writes a path itself with
  `(dispatch! {:type :ext-ui/set :path [:code] :value nil})` (paths: non-empty
  vector of keywords/strings; values: nil, string, boolean or number). Read the
  state while rendering and close over it in a button's `:on-click` to send it
  to the server half as an ordinary `:ext.<id>/*` event.
- **Rendering:** page output is sanitized before replicant renders it.
  Script-capable tags (`script`, `iframe`, `object`, `style`, …), string
  `on*` handlers, `innerHTML`/`srcdoc`, and `javascript:`/`vbscript:`/non-image
  `data:` URLs are removed. A throwing page renders an error box instead of
  breaking the client.

## Limits (v1)

- An extension that loops forever blocks the server thread. SCI can't be
  interrupted.
- Each process reads its own directory and its own `:extensions` list. A TUI
  joined to a server presents the commands, keybindings and prompt badges of
  the extensions enabled in *its* directory and forwards their events. The
  effects and tools run on the server, from the server's directory. On one
  machine these are the same files. Against a remote server, keep the two
  directories and lists in sync.
- Browser halves are loaded once per page load. After `/ext reload`, refresh
  the browser. The TUI has no browser-half equivalent.
- An extension can add system-prompt text, which steers the agent. The agent's
  tool calls still go through the rules.
