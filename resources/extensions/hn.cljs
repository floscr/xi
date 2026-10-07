;; Demo extension bundled with xi — enable with config.edn
;; `:demo-extensions ["hn.cljs"]`. Gives the agent an `hn_search` tool over the
;; Hacker News search API. Requests ask until a rule allows the host:
;;   {:match {:tool :net :extension "hn" :host "hn.algolia.com"} :action {:type :allow}}
;; Built step by step in docs/guide/extension-tutorial-http.md.
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
