# Tutorial: a web tool

Give the agent a `hn_search` tool that queries the Hacker News API and returns
the top stories for a term. This covers HTTP requests, JSON, and formatting a
result for the model.

## What you build

Ask "what did Hacker News say about Bun 1.3" and the agent calls
`hn_search`, which fetches `hn.algolia.com` and returns a short, numbered
list with titles, points and links.

## 1. The request

Create `~/.config/xi/extensions/hn.cljs`:

```clojure
(ns hn
  (:require [clojure.string :as str]
            [xi.api.http :as http]
            [xi.api.json :as json]
            [xi.api.promise :as p]))

(defn- search-url [query]
  (str "https://hn.algolia.com/api/v1/search?tags=story&hitsPerPage=8&query="
       (http/url-encode query)))

(defn- fetch-hits [ctx query]
  (-> (http/fetch ctx (search-url query) {:timeout-ms 15000})
      (p/then (fn [{:keys [ok? status body]}]
                (if ok?
                  (:hits (json/parse body))
                  (throw (ex-info (str "HTTP " status) {})))))))
```

`http/fetch` takes `ctx`, the URL, and options (`:method`, `:headers`,
`:body`, `:timeout-ms`). It resolves to a map with `:status`, `:ok?`,
`:headers`, `:body` and the final `:url` after redirects. `json/parse` turns
the body into Clojure data with keyword keys.

## 2. Format for the model

A tool result is text the model reads. Keep it short and regular:

```clojure
(defn- format-hit [i {:keys [title url points num_comments objectID]}]
  (str "[" (inc i) "] " title "\n"
       "    " (or url (str "https://news.ycombinator.com/item?id=" objectID)) "\n"
       "    " points " points · " num_comments " comments"))

(defn- format-hits [query hits]
  (if (empty? hits)
    (str "No stories for \"" query "\".")
    (str (count hits) " stories for \"" query "\":\n\n"
         (str/join "\n\n" (map-indexed format-hit hits))
         "\n\nUse the fetch tool to read one.")))
```

## 3. The tool

```clojure
(defn- hn-search [{:keys [query]} ctx]
  (if (str/blank? query)
    {:content [{:type "text" :text "Empty query."}] :is-error true}
    (-> (fetch-hits ctx query)
        (p/then (fn [hits]
                  {:content [{:type "text" :text (format-hits query hits)}]}))
        (p/catch (fn [e]
                   {:content [{:type "text" :text (str "Search failed: " (ex-message e))}]
                    :is-error true})))))

(def extension
  {:id :hn
   :tool-definitions
   [{:name "hn_search"
     :description "Search Hacker News stories. Returns titles, links, points and comment counts; read a story with the fetch tool."
     :input_schema {:type "object"
                    :properties {:query {:type "string"}}
                    :required ["query"]}}]
   :tool-registry {"hn_search" hn-search}})
```

Two habits worth keeping: validate the arguments and answer with
`:is-error true` for input the model should fix, and catch every rejection so
the model gets a readable failure instead of a stack trace.

## 4. Allow the host

Enable `hn.cljs` in `config.edn`, reload, and ask the agent to search. The
first call shows a dialog: an extension's network request asks by default,
and the dialog names the extension and the host. `a` allows this extension
and this host for the rest of the chat.

To stop asking for good:

```clojure
;; ~/.config/xi/rules.edn
{:match  {:tool :net :extension "hn" :host "hn.algolia.com"}
 :action {:type :allow}}
```

A rule names one host, so an extension that quietly starts talking to another
one asks again.

## Secrets

There is no environment access in the sandbox. An API key goes in a file in
the extension's data directory, which it may read without asking (with
`xi.api.fs` required as `fs`):

```clojure
(defn- api-key [ctx]
  (-> (fs/read ctx (str (fs/data-dir ctx) "/key.txt"))
      (p/then str/trim)))
```

Put the file at `~/.local/share/xi/extensions/<id>/key.txt`.

## When something is off

**The dialog never appears and the call fails.** The tool was called from
somewhere without a dialog, such as an effect or a one-shot `xi prompt`. Add
the rule.

**`json/parse` returns nothing useful.** Check `:ok?` and `:status` first; an
error page is not JSON. Print `(subs body 0 200)` into the error text while
developing.

**The model does not call the tool.** Its description is what the model goes
on. Say what the tool is for and when to prefer it over a general web search.

## Next

[Tutorial: a page in the browser](extension-tutorial-web.md) adds a web page.
