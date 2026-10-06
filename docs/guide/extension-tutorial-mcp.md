# Tutorial: your own MCP server

An extension can bring an MCP server that only it can call. Here a headless
browser from `chrome-devtools-mcp` backs one tool, `page_headings`, that lists
the headings of a web page after it rendered.

## What you build

The agent calls `page_headings` with a URL. The extension opens the page in a
headless browser through its private server, reads the headings with a script,
and returns them. The browser is started on the first call and stopped when
the extension unloads.

## 1. Declare the server

Create `~/.config/xi/extensions/headings.cljs`:

```clojure
(ns headings
  (:require [clojure.string :as str]
            [xi.api.mcp :as mcp]
            [xi.api.promise :as p]))

(def extension
  {:id :headings
   :mcp-servers
   {:browser {:command "npx"
              :args ["-y" "chrome-devtools-mcp@1.10.1"
                     "--headless" "--isolated" "--no-usage-statistics"]}}})
```

`:mcp-servers` takes the same entries as `mcp.edn`: a `:command` with
`:args` (and optionally `:env` and `:cwd`), or `:transport :http` with a
`:url`. The server is **private**: the agent never sees its tools, and no
other extension can call it. Pin the version; an upgrade is then a visible
change.

## 2. Call it

`mcp/call` takes `ctx`, the server's name from the map, the tool name, and
its arguments. It resolves to the tool's result, `{:content […]
:is-error …}`.

```clojure
(defn- text-of [result]
  (->> (:content result) (filter #(= "text" (:type %))) (map :text) (str/join "\n")))

(defn- page-headings [{:keys [url]} ctx]
  (-> (mcp/call ctx :browser "navigate_page" {:type "url" :url url})
      (p/then (fn [_]
                (mcp/call ctx :browser "evaluate_script"
                          {:function "() => [...document.querySelectorAll('h1,h2,h3')].map(h => h.tagName + ' ' + h.innerText.trim()).join('\\n')"})))
      (p/then (fn [result]
                {:content [{:type "text" :text (text-of result)}]
                 :is-error (boolean (:is-error result))}))
      (p/catch (fn [e]
                 {:content [{:type "text" :text (str "Failed: " (ex-message e))}]
                  :is-error true}))))
```

## 3. The tool

```clojure
(def extension
  {:id :headings
   :mcp-servers
   {:browser {:command "npx"
              :args ["-y" "chrome-devtools-mcp@1.10.1"
                     "--headless" "--isolated" "--no-usage-statistics"]}}
   :tool-definitions
   [{:name "page_headings"
     :description "List the h1-h3 headings of a web page after it rendered in a browser. Use for pages the fetch tool returns empty."
     :input_schema {:type "object"
                    :properties {:url {:type "string"}}
                    :required ["url"]}}]
   :tool-registry {"page_headings" page-headings}})
```

## 4. Trust the server

Enable the file, reload, ask the agent for the headings of a page. The first
call shows the MCP dialog with the server as `headings/browser`, the tool and
its arguments. `a` trusts it until its command line changes. Or trust it
without a call:

```text
/mcp trust headings/browser
```

For good, in `config.edn`:

```clojure
:trusted-mcp-servers ["headings/browser"]
```

`/mcp list` shows the server with its extension.

## Calling a shared server instead

If a server is already in `mcp.edn` and the agent uses it too, an extension
can call that one and share its state, for instance one browser with the
agent's tabs:

```clojure
(mcp/call ctx :chrome "list_pages" {})
```

The rules are the same: a trusted server runs, an untrusted one asks. Tools
listed under the entry's `:hidden-tools` are reachable this way without
being offered to the agent, for a server that has tools meant for an
extension rather than the model.

## When something is off

**The first call fails with "server exited".** Run the command by hand:
`npx -y chrome-devtools-mcp@1.10.1 --headless`. A missing browser or a
download failure prints its reason there.

**It asks on every call from an effect.** An effect has no dialog to answer.
Trust the server before using it from one.

**The file is rejected: "malformed :mcp-servers".** Names are simple keywords
and every entry is a stdio `:command` with string `:args`, or an `:http`
`:url`.

## Next

The [extension reference](extensions-reference.md) has every key, API
function and limit.
