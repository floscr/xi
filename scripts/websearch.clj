#!/usr/bin/env bb
;; Free web-search helper for xi's `web_search` tool.
;;
;; Fetches a DuckDuckGo-lite SERP over plain HTTP and extracts the organic
;; results with the jsoup Babashka pod (packages/jsoup.nix in the user's
;; dotfiles → `pod-jaydeesimon-jsoup` on PATH). No paid API, no headless
;; browser. Prints a single JSON object on stdout and always exits 0 — errors
;; are reported in the JSON so the caller (xi.ext.freesearch, running on Bun)
;; can parse them uniformly.
;;
;;   bb scripts/websearch.clj "<query>" [--limit N] [--recency hour|day|week|month|year]
;;
;; Output: {"query","engine","count","results":[{"title","url","snippet"}]}
;;     or: {"error":"…"}
(require '[babashka.pods :as pods]
         '[babashka.http-client :as http]
         '[babashka.fs :as fs]
         '[cheshire.core :as json]
         '[clojure.string :as str])

(def ^:private user-agent
  "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122 Safari/537.36")

(def ^:private recency->df
  {"hour" "d" "day" "d" "week" "w" "month" "m" "year" "y"})

(defn- fail! [msg]
  (println (json/generate-string {:error msg}))
  (System/exit 0))

(defn- load-jsoup! []
  (let [pod (or (some-> (fs/which "pod-jaydeesimon-jsoup") str)
                (let [vendored (str (fs/expand-home
                                     "~/.config/dotfiles/modules/scripts/deps/pod-jaydeesimon-jsoup.bin"))]
                  (when (fs/exists? vendored) vendored)))]
    (when-not pod
      (fail! "jsoup pod not found on PATH (pod-jaydeesimon-jsoup). Enable the jsoup dotfiles module."))
    (pods/load-pod pod)
    (require '[pod.jaydeesimon.jsoup :as jsoup])))

(defn- parse-args [args]
  (loop [opts {:limit 8} [a & more] args]
    (cond
      (nil? a) opts
      (= a "--limit")   (recur (assoc opts :limit (parse-long (or (first more) "8"))) (rest more))
      (= a "--recency") (recur (assoc opts :recency (first more)) (rest more))
      (str/blank? (:query opts)) (recur (assoc opts :query a) more)
      :else (recur opts more))))

(defn- decode-ddg-url
  "DDG-lite wraps every result in //duckduckgo.com/l/?uddg=<encoded>&rut=…
   Return the real target URL."
  [href]
  (if-let [enc (second (re-find #"[?&]uddg=([^&]+)" (or href "")))]
    (java.net.URLDecoder/decode enc "UTF-8")
    ;; not wrapped — normalise protocol-relative //host → https://host
    (cond
      (str/starts-with? (or href "") "//") (str "https:" href)
      :else href)))

(defn- blocked? [status body]
  (or (not= status 200)
      (let [low (str/lower-case (or body ""))]
        (some #(str/includes? low %)
              ["if this error persists" "unusual traffic" "verify you are human"
               "too many requests" "captcha"]))))

(defn- clean [s] (-> (or s "") (str/replace #"\s+" " ") str/trim))

(defn -main [& args]
  (let [{:keys [query limit recency]} (parse-args args)]
    (when (str/blank? query)
      (fail! "empty query"))
    (load-jsoup!)
    (let [jsoup (requiring-resolve 'pod.jaydeesimon.jsoup/select)
          enc   (java.net.URLEncoder/encode query "UTF-8")
          df    (get recency->df recency)
          url   (cond-> (str "https://lite.duckduckgo.com/lite/?q=" enc)
                  df (str "&df=" df))
          {:keys [status body]}
          (try
            (http/get url {:throw false
                           :headers {"User-Agent" user-agent
                                     "Accept-Language" "en-US,en;q=0.9"}})
            (catch Exception e {:status 0 :body (.getMessage e)}))]
      (when (blocked? status body)
        (fail! (str "DuckDuckGo blocked or unreachable (HTTP " status ").")))
      (let [links    (jsoup body "a.result-link")
            snippets (mapv (comp clean :text) (jsoup body "td.result-snippet"))
            results  (->> links
                          (map-indexed
                           (fn [i {:keys [text attrs]}]
                             {:title   (clean text)
                              :url     (decode-ddg-url (get attrs "href"))
                              :snippet (get snippets i "")}))
                          (filter #(and (not (str/blank? (:title %)))
                                        (str/starts-with? (str (:url %)) "http")))
                          (take (or limit 8))
                          vec)]
        (println (json/generate-string
                  {:query query :engine "duckduckgo-lite"
                   :count (count results) :results results}))))))

(apply -main *command-line-args*)
