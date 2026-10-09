(ns xi.server.usage
  "Server-side usage polling (xi.server.ws): every source — the built-in
   providers' subscriptions below plus the composed extensions'
   `:usage-sources` — is fetched at start, every five minutes, on demand
   (`refresh!`, throttled) and whenever the Claude login changes. Readings
   (xi.usage) are kept in memory; the sample history behind the charts is
   persisted at ~/.config/xi/state/usage-history.edn so a restart keeps the
   week's line.

   A source is {:id kw :fetch (fn [ctx] → Promise<[reading …]|nil>)}; a
   failing or nil source keeps its last readings. `ctx` is the app's
   {:get-state} (a user extension's guard adds its capability token). Readings
   are unique by :id, the built-in ones first: a pool extension listing every
   stored Claude login never doubles the live one's card."
  (:require [cljs.reader :as reader]
            [xi.providers.openai.auth :as codex-auth]
            [xi.providers.zen.auth :as zen-auth]
            [xi.session :as session]
            [xi.usage :as usage]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]))

(def ^:private poll-ms (* 5 60 1000))
(def ^:private refresh-throttle-ms (* 30 1000))
(def ^:private fetch-timeout-ms 15000)

(defonce ^:private !state
  (atom {:by-source {} :readings [] :history nil :fetched-at 0}))

;; ── History file ─────────────────────────────────────────────────────────────

(defonce ^:private file-override (atom nil))

(defn set-file!
  "Override the history file (nil restores the default). Test seam."
  [f]
  (reset! file-override f))

(defn- history-file []
  (or @file-override
      (path/join (os/homedir) ".config" "xi" "state" "usage-history.edn")))

(defn- load-history []
  (try
    (let [f (history-file)]
      (if (.existsSync fs f)
        (let [h (reader/read-string (.readFileSync fs f "utf8"))]
          (if (map? h) h {}))
        {}))
    (catch :default _ {})))

(defn- save-history! [h]
  (try
    (let [f   (history-file)
          tmp (str f ".tmp")]
      (.mkdirSync fs (path/dirname f) #js {:recursive true})
      (.writeFileSync fs tmp (str (pr-str h) "\n") #js {:mode 384})
      (.renameSync fs tmp f))
    (catch :default e
      (js/console.error "[usage] could not save the history:" (.-message e)))))

;; ── Fetch helpers ─────────────────────────────────────────────────────────────

(defn- env [k]
  (let [v (aget js/process.env k)]
    (when (and (string? v) (pos? (count v))) v)))

(defn- read-json [p]
  (try
    (when (.existsSync fs p)
      (js->clj (js/JSON.parse (.readFileSync fs p "utf8")) :keywordize-keys true))
    (catch :default _ nil)))

(defn- fetch-json
  "GET `url` → Promise of the keywordized JSON body, nil on any failure."
  [url headers]
  (-> (js/fetch url #js {:headers (clj->js headers)
                         :signal  (js/AbortSignal.timeout fetch-timeout-ms)})
      (.then (fn [^js res] (when (.-ok res) (.json res))))
      (.then (fn [data] (when data (js->clj data :keywordize-keys true))))
      (.catch (fn [_] nil))))

;; ── Built-in sources ──────────────────────────────────────────────────────────

(defn- claude-credentials-path []
  (path/join (session/claude-config-dir) ".credentials.json"))

(defn- claude-config-path
  "Claude Code keeps .claude.json in $CLAUDE_CONFIG_DIR when set, else in ~."
  []
  (path/join (if (env "CLAUDE_CONFIG_DIR") (session/claude-config-dir) (os/homedir))
             ".claude.json"))

;; ~/.claude.json carries every project's settings and grows large; its
;; account block is re-read only when the file changed.
(defonce ^:private !claude-account (atom nil)) ; [mtime account]

(defn- claude-account []
  (let [p     (claude-config-path)
        mtime (try (.-mtimeMs (.statSync fs p)) (catch :default _ nil))
        [m a] @!claude-account]
    (if (and mtime (= m mtime))
      a
      (let [a (:oauthAccount (read-json p))]
        (reset! !claude-account [mtime a])
        a))))

(defn- claude-login
  "The live Claude Code login: its token and what the card says about it."
  []
  (let [oauth (:claudeAiOauth (read-json (claude-credentials-path)))]
    (when-let [token (:accessToken oauth)]
      {:token      token
       :email      (:emailAddress (claude-account))
       :plan       (:subscriptionType oauth)
       :tier       (:rateLimitTier oauth)
       :expires-at (:expiresAt oauth)})))

(defn- fetch-claude [_]
  (when-let [{:keys [token] :as login} (claude-login)]
    (-> (fetch-json "https://api.anthropic.com/api/oauth/usage"
                    {"Authorization"  (str "Bearer " token)
                     "anthropic-beta" "oauth-2025-04-20"})
        (.then (fn [response]
                 (when response
                   (some-> (usage/claude-reading (assoc login :response response
                                                        :now (js/Date.now)))
                           vector)))))))

(defn- fetch-codex [_]
  (when (.existsSync fs (codex-auth/auth-path))
    (some-> (codex-auth/resolve-token)
            (.then (fn [{:keys [access-token account-id]}]
                     (fetch-json "https://chatgpt.com/backend-api/wham/usage"
                                 (cond-> {"Authorization" (str "Bearer " access-token)
                                          "Accept" "application/json"
                                          "User-Agent" "codex-cli"}
                                   account-id (assoc "ChatGPT-Account-Id" account-id)))))
            (.then (fn [response]
                     (when response
                       (some-> (usage/codex-reading {:response response :now (js/Date.now)})
                               vector))))
            (.catch (fn [_] nil)))))

(defn- fetch-ollama [_]
  (when-let [key (env "OLLAMA_API_KEY")]
    (let [headers {"Authorization" (str "Bearer " key)}]
      (-> (js/Promise.all #js [(fetch-json "https://ollama.com/api/balance" headers)
                               (fetch-json "https://ollama.com/api/usage?range=30d" headers)])
          (.then (fn [[balance usage-resp]]
                   (when balance
                     (some-> (usage/ollama-reading {:balance balance :usage usage-resp
                                                    :now (js/Date.now)})
                             vector))))))))

(defn- fetch-opencode [_]
  (when-let [key (zen-auth/api-key)]
    (-> (fetch-json "https://opencode.ai/zen/go/v1/usage"
                    {"Authorization" (str "Bearer " key)})
        (.then (fn [response]
                 (when response
                   (some-> (usage/opencode-reading {:response response :now (js/Date.now)})
                           vector)))))))

(def builtin-sources
  [{:id :claude   :fetch fetch-claude}
   {:id :codex    :fetch fetch-codex}
   {:id :ollama   :fetch fetch-ollama}
   {:id :opencode :fetch fetch-opencode}])

;; ── Polling ───────────────────────────────────────────────────────────────────

(defonce ^:private !fetched-at
  ;; Wall-clock ms of the last fetch attempt, throttling on-demand refreshes.
  (atom 0))

(defn- run-source
  "→ Promise<[source-id readings|nil]>; never rejects."
  [ctx {:keys [id fetch]}]
  (-> (js/Promise.resolve (try (fetch ctx) (catch :default _ nil)))
      (.then (fn [readings]
               [id (when (sequential? readings) (vec (remove nil? readings)))]))
      (.catch (fn [e]
                (js/console.error (str "[usage] " (name id) " failed: " (some-> e .-message)))
                [id nil]))))

(defn- integrate
  "The state after one poll: readings per source (a nil result keeps the
   source's last readings), the flat reading list in source order, the
   history extended and pruned."
  [{:keys [by-source history] :as st} results sources now]
  (let [by-source (reduce (fn [m [id readings]]
                            (if (nil? readings) m (assoc m id readings)))
                          by-source results)
        readings  (->> sources
                       (mapcat #(get by-source (:id %)))
                       (reduce (fn [[seen acc] r]
                                 (if (seen (:id r)) [seen acc] [(conj seen (:id r)) (conj acc r)]))
                               [#{} []])
                       second)
        history   (-> (or history (load-history))
                      (usage/record readings now)
                      (usage/prune now))]
    (assoc st :by-source by-source :readings readings :history history :fetched-at now)))

(defn- fetch-all!
  "Poll every source once; `on-change` runs when the readings changed."
  [{:keys [sources ctx on-change]}]
  (let [now     (js/Date.now)
        sources (into builtin-sources (when sources (sources)))]
    (reset! !fetched-at now)
    (-> (js/Promise.all (clj->js (map #(run-source (or ctx {}) %) sources)))
        (.then (fn [results]
                 (let [before @!state
                       after  (swap! !state integrate (map vec results) sources now)]
                   (when-not (= (:history before) (:history after))
                     (save-history! (:history after)))
                   (when (and on-change (not= (:readings before) (:readings after)))
                     (on-change)))))
        (.catch (fn [e] (js/console.error "[usage] poll failed:" (some-> e .-message)))))))

(defonce ^:private !poller (atom nil))

(defn start!
  "Begin polling: at once, every five minutes, and when the Claude credentials
   file changes (an account switch or token refresh). `sources` is a 0-arg fn
   returning the extensions' usage sources (read per poll, so a reloaded
   extension's source is picked up); `ctx` is handed to every fetch."
  [opts]
  (reset! !poller opts)
  (fetch-all! opts)
  (js/setInterval #(fetch-all! opts) poll-ms)
  (let [mtime #(try (.-mtimeMs (.statSync fs (claude-credentials-path))) (catch :default _ nil))
        seen  (atom (mtime))]
    (js/setInterval
     (fn []
       (let [m (mtime)]
         (when (not= m @seen)
           (reset! seen m)
           (fetch-all! opts))))
     10000)))

(defn refresh!
  "An on-demand poll, at most one per 30 seconds."
  []
  (when-let [opts @!poller]
    (when (> (- (js/Date.now) @!fetched-at) refresh-throttle-ms)
      (fetch-all! opts))))

;; ── Reads ─────────────────────────────────────────────────────────────────────

(defn fetched-at
  "Wall-clock ms of the last completed poll, 0 before the first."
  []
  (:fetched-at @!state))

(defn claude-summary
  "The sidebar ring's compact reading of the live Claude login
   ([:lobby :claude-usage]), nil until a poll succeeded."
  []
  (some->> (:readings @!state)
           (filter #(= :claude (:provider %)))
           first
           usage/claude-summary))

(defn snapshot
  "What the /usage page shows: {:readings :history :fetched-at}."
  []
  (let [{:keys [readings history fetched-at]} @!state]
    {:readings   readings
     :history    (or history {})
     :fetched-at fetched-at}))
