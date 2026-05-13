(ns xi.ext.perplexity
  "Perplexity web search extension — uses Perplexity Pro/Max subscription.
   Authenticates via saved token (shared with Pi) or macOS desktop app.
   Token cached at ~/.config/pi-perplexity/auth.json

   Ported from Pi's perplexity.ts extension.

   Commands:
     /perplexity-login [--force]  — authenticate and persist token

   Tools:
     web_search                   — search the web via Perplexity"
  (:require [clojure.string :as str]
            ["node:fs/promises" :as fsp]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:os" :as os]
            ["node:child_process" :as child-process]))

;; ── Constants ─────────────────────────────────────────────────────────────────

(def ^:private PERPLEXITY_USER_AGENT "Perplexity/641 CFNetwork/1568 Darwin/25.2.0")
(def ^:private PERPLEXITY_API_VERSION "2.18")
(def ^:private PERPLEXITY_ENDPOINT "https://www.perplexity.ai/rest/sse/perplexity_ask")
(def ^:private TOKEN_PATH (path/join (os/homedir) ".config" "pi-perplexity" "auth.json"))
(def ^:private MAX_SNIPPET_LENGTH 240)
(def ^:private MAX_BUN_STDOUT (* 50 1024 1024))

;; ── Token Storage ─────────────────────────────────────────────────────────────

(defn- load-token
  "Load saved OAuth token. Returns promise of token string or nil."
  []
  (-> (fsp/readFile TOKEN_PATH "utf8")
      (.then (fn [raw]
               (let [parsed (js->clj (js/JSON.parse raw) :keywordize-keys true)]
                 (when (and (= "oauth" (:type parsed))
                            (not (str/blank? (:access parsed))))
                   (:access parsed)))))
      (.catch (fn [_] nil))))

(defn- save-token
  "Save OAuth token to disk. Returns promise."
  [token & [email]]
  (let [data (cond-> {:type "oauth" :access token}
               email (assoc :email email))]
    (-> (fsp/mkdir (path/dirname TOKEN_PATH) #js {:recursive true})
        (.then #(fsp/writeFile TOKEN_PATH
                               (str (js/JSON.stringify (clj->js data) nil 2) "\n")
                               #js {:encoding "utf8" :mode 384}))
        (.then #(fsp/chmod TOKEN_PATH 384)))))

(defn- clear-token
  "Delete saved token. Returns promise."
  []
  (-> (fsp/rm TOKEN_PATH #js {:force true})
      (.catch (fn [_] nil))))

;; ── Desktop App Token Extraction ──────────────────────────────────────────────

(defn- extract-from-desktop-app
  "Try to borrow token from Perplexity macOS desktop app. Returns promise of string or nil."
  []
  (if (not= (.-platform js/process) "darwin")
    (js/Promise.resolve nil)
    (js/Promise.
     (fn [resolve _reject]
       (child-process/execFile
        "defaults" #js ["read" "ai.perplexity.mac" "authToken"]
        (fn [err stdout _stderr]
          (if err
            (resolve nil)
            (let [token (some-> stdout str/trim)]
              (if (and token (not= token "(null)") (not (str/blank? token)))
                (resolve token)
                (resolve nil))))))))))

;; ── Authentication ────────────────────────────────────────────────────────────

(defn- authenticate
  "Get a valid JWT. Tries: cached token → desktop app.
   Returns promise of token string, or rejects."
  []
  (-> (load-token)
      (.then (fn [cached]
               (if cached
                 cached
                 ;; Try desktop app
                 (-> (extract-from-desktop-app)
                     (.then (fn [desktop-token]
                              (if desktop-token
                                (-> (save-token desktop-token)
                                    (.then (fn [_] desktop-token)))
                                (js/Promise.reject
                                 (js/Error. "No Perplexity token found. Install the Perplexity desktop app and sign in, or manually create ~/.config/pi-perplexity/auth.json with {\"type\":\"oauth\",\"access\":\"YOUR_TOKEN\"}")))))))))))

;; ── Bun Subprocess Fetch ──────────────────────────────────────────────────────

(def ^:private BUN_SCRIPT
  "const c = JSON.parse(await Bun.stdin.text());
try {
  const r = await fetch(c.url, { method: 'POST', headers: c.headers, body: c.body });
  const t = await r.text();
  process.stdout.write(JSON.stringify({ s: r.status, b: t }));
} catch (e) {
  process.stdout.write(JSON.stringify({ s: 0, b: String(e?.message ?? e) }));
}")

(defn- fetch-via-bun
  "Use Bun subprocess to make HTTP request (passes Cloudflare).
   Returns promise of {:status number :body string}."
  [url headers body]
  (js/Promise.
   (fn [resolve reject]
     (let [child (child-process/spawn
                  "bun" #js ["-e" BUN_SCRIPT]
                  #js {:stdio #js ["pipe" "pipe" "ignore"]
                       :env #js {:HOME (aget js/process.env "HOME")
                                 :PATH (aget js/process.env "PATH")}})
           chunks (atom [])
           total-len (atom 0)]
       ;; Write input
       (.write (.-stdin child)
               (js/JSON.stringify (clj->js {:url url :headers headers :body body})))
       (.end (.-stdin child))
       ;; Collect stdout
       (.on (.-stdout child) "data"
            (fn [chunk]
              (swap! total-len + (.-length chunk))
              (when (<= @total-len MAX_BUN_STDOUT)
                (swap! chunks conj chunk))))
       ;; On close, parse result
       (.on child "close"
            (fn [_code]
              (let [stdout (.toString (js/Buffer.concat (clj->js @chunks)) "utf8")]
                (try
                  (let [parsed (js->clj (js/JSON.parse stdout) :keywordize-keys true)]
                    (if (and (number? (:s parsed)) (string? (:b parsed)))
                      (resolve {:status (:s parsed) :body (:b parsed)})
                      (reject (js/Error. "Bun subprocess response missing required fields."))))
                  (catch :default _
                    (reject (js/Error. (str "Bun subprocess returned invalid output: "
                                            (subs stdout 0 (min 200 (count stdout)))))))))))
       (.on child "error" reject)))))

;; ── SSE Parsing ───────────────────────────────────────────────────────────────

(defn- try-parse-json
  "Parse JSON string to clj map, or nil on failure."
  [s]
  (try (js->clj (js/JSON.parse s) :keywordize-keys true)
       (catch :default _ nil)))

(defn- parse-sse-events
  "Parse SSE text into sequence of event maps."
  [text]
  (let [lines (str/split-lines text)]
    (loop [remaining lines
           data-lines []
           events []]
      (if (empty? remaining)
        ;; Flush any remaining data
        (if (seq data-lines)
          (let [payload (str/trim (str/join "\n" data-lines))]
            (if (or (str/blank? payload) (= payload "[DONE]"))
              events
              (if-let [parsed (try-parse-json payload)]
                (conj events parsed)
                events)))
          events)
        (let [line (first remaining)
              rest-lines (rest remaining)]
          (cond
            ;; Empty line = end of event
            (str/blank? line)
            (if (seq data-lines)
              (let [payload (str/trim (str/join "\n" data-lines))]
                (if (or (str/blank? payload) (= payload "[DONE]"))
                  (recur rest-lines [] events)
                  (let [parsed (try-parse-json payload)]
                    (recur rest-lines []
                           (if parsed (conj events parsed) events)))))
              (recur rest-lines [] events))

            ;; Data line
            (str/starts-with? line "data:")
            (recur rest-lines
                   (conj data-lines (str/trim (subs line 5)))
                   events)

            ;; Other lines (event:, id:, etc.) - skip
            :else
            (recur rest-lines data-lines events)))))))

;; ── Stream Merging ────────────────────────────────────────────────────────────

(defn- merge-markdown-block [existing incoming]
  (let [current-chunks (or (:chunks existing) [])
        incoming-chunks (:chunks incoming)
        offset (:chunk_starting_offset incoming)
        merged-chunks (cond
                        (nil? incoming-chunks) current-chunks
                        (and (number? offset) (pos? offset))
                        (into (vec (take offset current-chunks)) incoming-chunks)
                        :else (vec incoming-chunks))
        merged-answer (or (:answer incoming)
                          (when (seq merged-chunks) (str/join "" merged-chunks))
                          (:answer existing))]
    (cond-> (merge existing incoming)
      true (assoc :chunks merged-chunks)
      merged-answer (assoc :answer merged-answer))))

(defn- merge-single-block [existing incoming]
  (let [merged (merge existing incoming)]
    (cond-> merged
      (or (:markdown_block existing) (:markdown_block incoming))
      (assoc :markdown_block (merge-markdown-block
                              (or (:markdown_block existing) {})
                              (or (:markdown_block incoming) {})))

      (or (:web_result_block existing) (:web_result_block incoming))
      (assoc :web_result_block
             {:web_results (or (get-in incoming [:web_result_block :web_results])
                               (get-in existing [:web_result_block :web_results])
                               [])}))))

(defn- merge-blocks [existing incoming]
  (reduce
   (fn [result block]
     (if-not (:intended_usage block)
       (conj result block)
       (let [idx (some (fn [[i b]]
                         (when (= (:intended_usage b) (:intended_usage block)) i))
                       (map-indexed vector result))]
         (if idx
           (assoc result idx (merge-single-block (nth result idx) block))
           (conj result block)))))
   (vec existing)
   incoming))

(defn- merge-event [existing incoming]
  (let [merged (merge existing incoming)]
    (cond-> merged
      (or (:blocks existing) (:blocks incoming))
      (assoc :blocks (merge-blocks (or (:blocks existing) [])
                                    (or (:blocks incoming) [])))

      (or (seq (:sources_list existing)) (seq (:sources_list incoming)))
      (assoc :sources_list (into (vec (or (:sources_list existing) []))
                                  (or (:sources_list incoming) []))))))

;; ── Result Extraction ─────────────────────────────────────────────────────────

(defn- extract-text-from-block [event match-fn]
  (some (fn [block]
          (let [usage (or (:intended_usage block) "")]
            (when (match-fn usage)
              (let [md (:markdown_block block)]
                (when md
                  (or (when (and (:answer md) (not (str/blank? (:answer md))))
                        (str/trim (:answer md)))
                      (when (seq (:chunks md))
                        (let [chunk-text (str/trim (str/join "" (:chunks md)))]
                          (when (not (str/blank? chunk-text))
                            chunk-text)))))))))
        (or (:blocks event) [])))

(defn- extract-answer [event]
  (or (extract-text-from-block event #(str/includes? % "markdown"))
      (extract-text-from-block event #(= % "ask_text"))
      (some-> (:text event) str/trim)
      ""))

(defn- normalize-url-for-dedup [url]
  (-> (or url "") str/trim (str/replace #"/$" "") str/lower-case))

(defn- dedupe-sources [sources]
  (let [seen (atom #{})]
    (filterv (fn [source]
               (let [url (some-> (:url source) str/trim)]
                 (if (str/blank? url)
                   true
                   (let [key (normalize-url-for-dedup url)]
                     (if (contains? @seen key)
                       false
                       (do (swap! seen conj key) true))))))
             sources)))

(defn- extract-sources [event]
  (let [web-block (some #(when (= (:intended_usage %) "web_results") %) (or (:blocks event) []))
        block-sources (get-in web-block [:web_result_block :web_results])]
    (if (seq block-sources)
      (dedupe-sources block-sources)
      ;; Fallback to sources_list
      (dedupe-sources
       (mapv (fn [s]
               (cond-> {}
                 (:title s) (assoc :name (:title s))
                 (:url s) (assoc :url (:url s))
                 (:snippet s) (assoc :snippet (:snippet s))
                 (:date s) (assoc :timestamp (:date s))))
             (or (:sources_list event) []))))))

;; ── Formatting ────────────────────────────────────────────────────────────────

(defn- truncate-snippet [snippet]
  (let [normalized (-> snippet (str/replace #"\s+" " ") str/trim)]
    (if (<= (count normalized) MAX_SNIPPET_LENGTH)
      normalized
      (str (subs normalized 0 (dec MAX_SNIPPET_LENGTH)) "…"))))

(defn- humanize-age [timestamp]
  (if (str/blank? timestamp)
    "unknown"
    (let [parsed (js/Date.parse timestamp)]
      (if (js/isNaN parsed)
        "unknown"
        (let [diff-ms (max 0 (- (js/Date.now) parsed))
              diff-s (js/Math.floor (/ diff-ms 1000))]
          (cond
            (< diff-s 60)    "just now"
            (< diff-s 3600)  (str (js/Math.floor (/ diff-s 60)) "m ago")
            (< diff-s 86400) (str (js/Math.floor (/ diff-s 3600)) "h ago")
            :else            (str (js/Math.floor (/ diff-s 86400)) "d ago")))))))

(defn- format-source [source index]
  (let [title (or (some-> (:name source) str/trim) "Untitled source")
        age (humanize-age (:timestamp source))
        lines [(str "[" (inc index) "] " title " (" age ")")]]
    (str/join "\n"
              (cond-> lines
                (not (str/blank? (:url source)))
                (conj (str "    " (str/trim (:url source))))
                (not (str/blank? (:snippet source)))
                (conj (str "    " (truncate-snippet (:snippet source))))))))

(defn- format-for-llm [answer sources display-model uuid limit]
  (let [source-limit (if (and (number? limit) (pos? limit))
                       (min limit (count sources))
                       (count sources))
        limited (take source-limit sources)
        source-section (if (empty? limited)
                         "0 sources\n(no sources returned)"
                         (str (count limited) " sources\n"
                              (str/join "\n\n" (map-indexed #(format-source %2 %1) limited))))
        meta-lines [(str "Provider: perplexity (oauth)")
                    (str "Model: " (or display-model "unknown"))]]
    (str/join "\n"
              (cond-> ["## Answer"
                       (if (str/blank? answer) "No answer returned." (str/trim answer))
                       ""
                       "## Sources"
                       source-section
                       ""
                       "## Meta"]
                true (into meta-lines)
                uuid (conj (str "Request ID: " uuid))))))

;; ── Search Request ────────────────────────────────────────────────────────────

(defn- build-request-body [query recency]
  (let [tz (or (some-> (js/Intl.DateTimeFormat.)
                       (.resolvedOptions)
                       (.-timeZone))
               "UTC")]
    (clj->js
     {:query_str query
      :params {:query_str query
               :search_focus "internet"
               :mode "copilot"
               :model_preference "pplx_pro_upgraded"
               :sources ["web"]
               :attachments []
               :frontend_uuid (js/crypto.randomUUID)
               :frontend_context_uuid (js/crypto.randomUUID)
               :version PERPLEXITY_API_VERSION
               :language "en-US"
               :timezone tz
               :search_recency_filter (or recency nil)
               :is_incognito true
               :use_schematized_api true
               :skip_search_enabled true}})))

(defn- build-request-headers [jwt]
  {"Authorization" (str "Bearer " jwt)
   "Content-Type" "application/json"
   "Accept" "text/event-stream"
   "Origin" "https://www.perplexity.ai"
   "Referer" "https://www.perplexity.ai/"
   "User-Agent" PERPLEXITY_USER_AGENT
   "X-App-ApiClient" "default"
   "X-App-ApiVersion" PERPLEXITY_API_VERSION
   "X-Perplexity-Request-Reason" "submit"
   "X-Request-ID" (js/crypto.randomUUID)})

(defn- search-perplexity
  "Execute Perplexity search. Returns promise of {:answer :sources :display-model :uuid}."
  [query recency jwt]
  (let [headers (build-request-headers jwt)
        body (js/JSON.stringify (build-request-body query recency))]
    (-> (fetch-via-bun PERPLEXITY_ENDPOINT headers body)
        (.then (fn [{:keys [status body]}]
                 (cond
                   (zero? status)
                   (js/Promise.reject (js/Error. (str "Network error: " body)))

                   (or (= status 401) (= status 403))
                   (js/Promise.reject
                    (js/Error. "Perplexity rejected authentication (401/403). Run /perplexity-login --force to re-authenticate."))

                   (= status 429)
                   (js/Promise.reject
                    (js/Error. "Perplexity rate limited this request (429). Wait a bit, then retry."))

                   (not= status 200)
                   (js/Promise.reject
                    (js/Error. (str "Perplexity request failed with HTTP " status)))

                   (str/blank? body)
                   (js/Promise.reject
                    (js/Error. "Perplexity returned an empty response."))

                   :else
                   (let [events (parse-sse-events body)
                         snapshot (reduce merge-event {} events)
                         _ (when (or (:error_code snapshot) (:error_message snapshot))
                             (throw (js/Error. (or (:error_message snapshot)
                                                   (str "Perplexity stream error: " (:error_code snapshot))))))
                         answer (extract-answer snapshot)
                         sources (extract-sources snapshot)]
                     (when (and (str/blank? answer) (empty? sources))
                       (throw (js/Error. "Perplexity returned no answer and no sources for this query.")))
                     {:answer (if (str/blank? answer) "No answer text returned by Perplexity." answer)
                      :sources sources
                      :display-model (:display_model snapshot)
                      :uuid (:uuid snapshot)})))))))

;; ── Extension ─────────────────────────────────────────────────────────────────

(def extension
  {:name "perplexity"
   :commands [{:name "perplexity-login"
               :description "Authenticate Perplexity and persist token"
               :handler (fn [{:keys [args]}]
                          (let [tokens (filterv (complement str/blank?)
                                               (str/split (or args "") #"\s+"))
                                force? (some #{"--force" "-f"} tokens)]
                            (-> (if force?
                                  (clear-token)
                                  (js/Promise.resolve))
                                (.then authenticate)
                                (.then (fn [_]
                                         (js/console.log "[perplexity] Login successful. Token saved.")))
                                (.catch (fn [err]
                                          (js/console.error "[perplexity] Login failed:" (.-message err)))))))}]

   :tools [{:name "web_search"
            :description "Search the web using Perplexity. Returns an AI-generated answer with cited web sources. Use this for any question that needs current/real-time information, facts you're unsure about, or research tasks."
            :input_schema {:type "object"
                           :properties {:query {:type "string"
                                                :description "Search query"}
                                        :recency {:type "string"
                                                  :enum ["hour" "day" "week" "month" "year"]
                                                  :description "Filter results by recency"}
                                        :limit {:type "number"
                                                :description "Max sources to return (1-50)"}}
                           :required ["query"]}
            :execute (fn [{:keys [query recency limit]}]
                       (let [start (js/Date.now)]
                         (-> (authenticate)
                             (.then (fn [jwt]
                                      (search-perplexity query recency jwt)))
                             (.then (fn [{:keys [answer sources display-model uuid]}]
                                      (let [source-count (if (and (number? limit) (pos? limit))
                                                           (min limit (count sources))
                                                           (count sources))
                                            formatted (format-for-llm answer sources display-model uuid limit)]
                                        {:content [{:type "text" :text formatted}]})))
                             (.catch (fn [err]
                                       {:content [{:type "text"
                                                   :text (str "Perplexity search failed: " (.-message err))}]
                                        :is-error true})))))}]})
