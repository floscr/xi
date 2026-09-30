# User extensions

Drop a `.cljs` file into `~/.config/xi/extensions/` and an already-built xi
loads it at startup. No build step is needed. User extensions have the same
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

## Files

- Each **top-level** `*.cljs` file in `~/.config/xi/extensions/` is one
  extension, loaded alphabetically after the built-ins and MCP servers.
- A file defines `extension`: the extension map for the server side (tools,
  handlers, commands, fx, system prompt, …).
- A file may require its own sibling namespaces: `(:require [notes.util])` loads
  `~/.config/xi/extensions/notes/util.cljs`.
- `web-extension` (a browser half) is collected but not loaded yet.

`/ext list` shows loaded extensions. `/ext reload` re-reads the directory. Tool
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
| `xi.api.http` | `(fetch ctx url {:method :headers :body})` (http(s) only) | `{:tool :net :host …}` |
| `xi.api.promise` | `then` `catch` `all` `resolve` `reject` `delay` | (no request) |

`.then` interop is blocked in the sandbox, so chain Promises with
`xi.api.promise`. Relative paths resolve against the room's cwd, or against
the data dir outside a room.

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

Allowed map keys: `:id :init :handlers :fx :commands :tool-definitions
:tool-registry :system-prompt :keybindings :prompt-badge :on-shutdown
:on-enable :on-disable :remove-tools` (plus the web keys). `:tool-gate` and
`:event-hooks` are rejected, because policy belongs to the rules engine. `:id`
and tool names must not clash with anything already loaded.

## Limits (v1)

- An extension that loops forever blocks the server thread. SCI can't be
  interrupted.
- User tools are offered to Claude models only. The openai/zen/ollama
  providers still only advertise built-in tools.
- Each process reads its own directory. A TUI connected to a remote server
  shows *its* extensions' commands, not the server's.
- Browser halves (`web-extension`) aren't loaded yet.
- An extension can add system-prompt text, which steers the agent. The agent's
  tool calls still go through the rules.
