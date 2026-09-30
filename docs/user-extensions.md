# User extensions

Put a `.cljs` file into `~/.config/xi/extensions/`, list its name under
`:extensions` in `~/.config/xi/rules.edn`, and an already-built xi loads it at
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

A file in the directory is loaded only when the **global** rules file names it:

```clojure
;; ~/.config/xi/rules.edn
{:version 1
 :extensions ["notes.cljs"]
 :rules []}
```

- `:extensions` is a vector of top-level file names. A file that isn't listed
  is never read or evaluated. It is logged at startup
  (`[user-ext] not enabled (…): foo.cljs`) and reported by `/ext reload`.
- With no rules file, no `:extensions` key, or an invalid rules file, nothing
  loads.
- Only the global file counts. `:extensions` in a repo's `.xi/rules.edn` is
  ignored, so a checked-out project can't enable anything.
- The list lives in the rules file because agents can't change it: writes
  under `~/.config/xi` are hard-blocked, and a change to any other xi rules
  file (e.g. a dotfiles source copied into place) asks every time (see
  [rules.md](rules.md)). Dropping a file into the directory is therefore not
  enough to get code loaded.
- The list names files, not contents. Editing a file that is already enabled
  is an ordinary write under the normal write rules.

## Files

- Each enabled **top-level** `*.cljs` file in `~/.config/xi/extensions/` is one
  extension, loaded alphabetically after the built-ins and MCP servers.
- A file defines `extension`: the extension map for the server side (tools,
  handlers, commands, fx, system prompt, …).
- A file may require its own sibling namespaces: `(:require [notes.util])` loads
  `~/.config/xi/extensions/notes/util.cljs`.
- `<name>/web.cljs` defining `web-extension` is the extension's optional
  **browser half** (see [Browser halves](#browser-halves)).

`/ext list` shows loaded extensions. `/ext reload` re-reads the enabled list
and the directory. Tool
changes apply on the next turn. Handler, command and keybinding changes need a
server restart, the same as for built-in extensions. Rejected files are logged
with the reason (`[user-ext] rejected foo.cljs: …`) and reported by
`/ext reload`.

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

| Namespace | Functions | Request |
|---|---|---|
| `xi.api.fs` | `read` `write` `list` `exists?` `data-dir` | `{:tool :read/:write/:ls :path …}` |
| `xi.api.sh` | `(sh ctx "cmd" "arg" …)` (optional `{:dir …}` before the argv) | `{:tool :sh :cli :command :argv}` |
| `xi.api.http` | `(fetch ctx url {:method :headers :body :timeout-ms})` (http(s) only) → `{:status :ok? :url :headers :body}`; `url-encode` `url-decode` (pure) | `{:tool :net :host …}` |
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
  events plus `:ui/status`. `:prompt/submit` is also allowed from **commands
  and keybindings** (user-initiated), but never from tool fns, so an agent
  can't drive the turn loop through an extension tool. This covers
  `dispatch!`, handler effects and keybinding events. Anything else is dropped
  and logged.
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
:on-enable :on-disable`. `:tool-gate`, `:event-hooks` and `:remove-tools` are
rejected, because policy belongs to the rules engine. `:id` and tool names
must not clash with anything already loaded, built-ins included.

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
  `xi.web.views` helpers, and the `ui.*` components (without the ones that
  touch `js/window`).

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
- **Rendering:** page output is sanitized before replicant renders it.
  Script-capable tags (`script`, `iframe`, `object`, `style`, …), string
  `on*` handlers, `innerHTML`/`srcdoc`, and `javascript:`/`vbscript:`/non-image
  `data:` URLs are removed. A throwing page renders an error box instead of
  breaking the client.

## Limits (v1)

- An extension that loops forever blocks the server thread. SCI can't be
  interrupted.
- User tools are offered to Claude models only. The openai/zen/ollama
  providers still only advertise built-in tools.
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
