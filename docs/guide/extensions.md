# Extensions

An extension is one ClojureScript file in `~/.config/xi/extensions/`. It can
give the agent a tool, give you a slash command or a keyboard shortcut, react
to what happens in a chat, add to the system prompt, or add a page to the web
client. No build step, no restart.

## The smallest extension

```clojure
;; ~/.config/xi/extensions/hello.cljs
(ns hello)

(def extension
  {:id :hello
   :commands [{:name "hello"
               :description "Say hello"
               :handler (fn [_state {:keys [room-id]}]
                          {:effects [[:app/dispatch {:type :ui/status
                                                     :room-id room-id
                                                     :text "Hello from an extension."}]]})}]})
```

Enable it by listing the file in your [config](configuration.md):

```clojure
;; ~/.config/xi/config.edn
{:type       :xi/config
 :version    1
 :extensions ["hello.cljs"]}
```

Start Xi (or run `/ext reload` in a running one) and type `/hello`. The
status line shows the text.

A file that is not listed is never read. This is deliberate: the agent cannot
write to `~/.config/xi`, so nothing the agent does, and nothing in a project
you cloned, can get code loaded. Only you can.

## Demo extensions

Xi ships a few working extensions. Enable one by its file name under
`:demo-extensions`; no file to copy:

```clojure
;; ~/.config/xi/config.edn
{:type            :xi/config
 :version         1
 :demo-extensions ["notes.cljs" "hn.cljs"]}
```

| File | Gives you | Built in |
| --- | --- | --- |
| `notes.cljs` | A `notes_add` tool, a `/notes` command and a Notes page in the web client | [Tutorial: a page in the browser](extension-tutorial-web.md) |
| `ping.cljs` | `Ctrl+Shift+N` or `/ping` toggles a desktop notification when a turn ends | [Tutorial: a command and a key](extension-tutorial-command.md) |
| `hn.cljs` | An `hn_search` tool for Hacker News | [Tutorial: a web tool](extension-tutorial-http.md) |

They run like your own extensions: sandboxed, through the rules, and
reloaded by `/ext reload`. Their source is in `resources/extensions/` of the
Xi package. An extension of yours cannot take the id or a tool name of an
enabled demo.

## What an extension can add

| Key | Gives you |
| --- | --- |
| `:tool-definitions` + `:tool-registry` | A tool the agent can call |
| `:commands` | A `/command` you can type |
| `:keybindings` | A key in the terminal client |
| `:handlers` | Code that runs when something happens (a turn ends, a dialog opens, …) |
| `:fx` | Side effects: read a file, run a program, make a request |
| `:system-prompt` | Text added to the agent's instructions |
| `:prompt-badge` | A marker next to the prompt, for state you want to see |
| `:init` | The extension's own state, per chat or per process |
| `:mcp-servers` | MCP servers only this extension can call |
| a `<name>/web.cljs` file | A page and menu entries in the web client |

The tutorials go through each of these with a working example:

1. [A tool](extension-tutorial-tool.md): the agent writes to a notes file
2. [A command and a key](extension-tutorial-command.md): a desktop notification when a turn ends
3. [A web tool](extension-tutorial-http.md): search Hacker News from a tool
4. [A page in the browser](extension-tutorial-web.md): the notes, with a form
5. [Your own MCP server](extension-tutorial-mcp.md): a headless browser for one tool
6. [Users and roles](extension-tutorial-roles.md): roles in `config.edn`, rules per role, and an extension that grants them

## How an extension runs

Extensions run **sandboxed**. There is no access to the file system, to
processes, to the network or to environment variables, except through a small
API, `xi.api.*`:

| Namespace | For |
| --- | --- |
| `xi.api.fs` | Read, write and list files; the extension's own data directory |
| `xi.api.sh` | Run a program |
| `xi.api.http` | Make an HTTP request |
| `xi.api.mcp` | Call an MCP server's tool |
| `xi.api.dialog` | Ask the user something |
| `xi.api.json` | Parse and produce JSON |
| `xi.api.promise` | Chain the promises the other functions return |

Every call through `fs`, `sh`, `http` or `mcp` goes through the
[rules](rules.md), tagged with the extension's id, like an agent's tool call:
the extension's own data directory is free, credential paths are blocked, and
everything else asks until a rule allows it. So an extension can do no more on
your machine than you let the agent do, and you can see in `/rules` what you
allowed it.

Code also runs with a few guards on Xi's state: an extension can change its
own state slice and nothing else, and can dispatch its own events plus a few
chosen ones. A throwing extension is logged, not fatal.

## Editing and reloading

Edit the file and run `/ext reload`. Xi evaluates the file again and swaps the
new code in while the server runs: handlers, commands and effects apply at
once, tools from the next turn, a web page on the next page load. The
extension's state is kept. If the file does not evaluate, Xi reports why and
unloads the old version, so nothing half-old keeps running.

An agent can call the same thing as a tool, `ext_reload`, which is how you
have the agent help you write an extension: it edits the file, reloads, and
sees the error if there is one.

`/ext list` shows what is loaded. `/ext disable <id>` turns one off for the
session.

## Where things live

| Path | What |
| --- | --- |
| `~/.config/xi/extensions/<name>.cljs` | The extension |
| `~/.config/xi/extensions/<name>/` | Its helper namespaces and its web half, `web.cljs` |
| `~/.local/share/xi/extensions/<id>/` | Its data directory; `(fs/data-dir ctx)` returns it |

An extension's id (`:id :notes`) is what the rules and the data directory use.
It must not clash with another loaded extension or with a built-in one.

## Next

Start with [Tutorial: a tool](extension-tutorial-tool.md). Everything a map
may contain, with every API function, is in the
[extension reference](extensions-reference.md).
