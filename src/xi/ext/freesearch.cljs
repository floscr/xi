(ns xi.ext.freesearch
  "Free `web_search` tool — no paid API, no headless browser.

   Shells out to a Babashka helper (scripts/websearch.clj) that fetches a
   DuckDuckGo-lite SERP over plain HTTP and extracts organic results with the
   jsoup Babashka pod (the `pod-jaydeesimon-jsoup` CSS-select pod packaged in
   the user's dotfiles). Returns a ranked list of {title,url,snippet}; the
   agent then reads promising hits with the `fetch` tool.

   Requires `bb` and `pod-jaydeesimon-jsoup` on PATH. The helper path is
   resolved relative to the compiled script (target/main.js → ../scripts) and
   can be overridden with XI_WEBSEARCH_SCRIPT."
  (:require [clojure.string :as str]
            ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:path" :as path]))

(def ^:private MAX_SNIPPET_LENGTH 300)
(def ^:private EXEC_TIMEOUT_MS 25000)

(defn- resolve-script
  "Locate scripts/websearch.clj — env override, then a path relative to the
   compiled script (target/main.js), then cwd."
  []
  (let [script-dir (when (exists? js/__dirname) js/__dirname)
        candidates (cond-> []
                     (aget js/process.env "XI_WEBSEARCH_SCRIPT")
                     (conj (aget js/process.env "XI_WEBSEARCH_SCRIPT"))
                     script-dir
                     (conj (.resolve path script-dir ".." "scripts" "websearch.clj"))
                     true
                     (conj (.resolve path (.cwd js/process) "scripts" "websearch.clj")))]
    (some (fn [p] (when (fs/existsSync p) p)) candidates)))

(defn- run-helper
  "Spawn `bb websearch.clj …`. Resolves the parsed JSON, or a map with :error."
  [script {:keys [query recency limit]}]
  (js/Promise.
   (fn [resolve _reject]
     (let [args (cond-> #js [script query]
                  (number? limit)          (.concat #js ["--limit" (str limit)])
                  (not (str/blank? recency)) (.concat #js ["--recency" recency]))]
       (cp/execFile "bb" args
                    #js {:maxBuffer (* 16 1024 1024) :timeout EXEC_TIMEOUT_MS}
                    (fn [err stdout _stderr]
                      (cond
                        err
                        (resolve #js {:error (str "web search helper failed: " (.-message err))})
                        :else
                        (try
                          (resolve (js/JSON.parse stdout))
                          (catch :default e
                            (resolve #js {:error (str "could not parse web search output: " (.-message e))}))))))))))

(defn- truncate-snippet [snippet]
  (let [s (-> (or snippet "") (str/replace #"\s+" " ") str/trim)]
    (if (<= (count s) MAX_SNIPPET_LENGTH)
      s
      (str (subs s 0 (dec MAX_SNIPPET_LENGTH)) "…"))))

(defn- format-result [r index]
  (let [title (or (some-> (aget r "title") str/trim) "Untitled")
        url (aget r "url")
        snippet (truncate-snippet (aget r "snippet"))]
    (str/join "\n"
              (cond-> [(str "[" (inc index) "] " title)]
                (not (str/blank? url)) (conj (str "    " url))
                (not (str/blank? snippet)) (conj (str "    " snippet))))))

(defn- format-for-llm [data]
  (let [results (aget data "results")
        n (count results)]
    (if (zero? n)
      (str "No results found for \"" (aget data "query") "\".")
      (str n " result" (when (> n 1) "s") " for \"" (aget data "query")
           "\" (via DuckDuckGo):\n\n"
           (str/join "\n\n" (map-indexed #(format-result %2 %1) results))
           "\n\nUse the `fetch` tool to read any of these pages in full."))))

(defn- web-search [{:keys [query recency limit]} _ctx]
  (if-let [script (resolve-script)]
    (-> (run-helper script {:query query :recency recency :limit limit})
        (.then (fn [data]
                 (if-let [err (aget data "error")]
                   {:content [{:type "text" :text (str "Web search failed: " err)}]
                    :is-error true}
                   {:content [{:type "text" :text (format-for-llm data)}]})))
        (.catch (fn [err]
                  {:content [{:type "text"
                              :text (str "Web search failed: " (.-message err))}]
                   :is-error true})))
    (js/Promise.resolve
     {:content [{:type "text"
                 :text "Web search unavailable: scripts/websearch.clj not found (set XI_WEBSEARCH_SCRIPT)."}]
      :is-error true})))

(def ^:private web-search-def
  {:name "web_search"
   :description "Search the web via DuckDuckGo. Returns a ranked list of results (title, URL, snippet) — no synthesized answer. Use it for current information, news, prices, docs, or facts you're unsure about, then read promising hits with the `fetch` tool."
   :input_schema {:type "object"
                  :properties {:query {:type "string"
                                       :description "Search query"}
                               :recency {:type "string"
                                         :enum ["hour" "day" "week" "month" "year"]
                                         :description "Filter results by recency"}
                               :limit {:type "number"
                                       :description "Max results to return (default 8)"}}
                  :required ["query"]}})

(def extension
  {:id               :freesearch
   :tool-definitions [web-search-def]
   :tool-registry    {"web_search" web-search}})
