(ns xi.providers.fake
  "Scripted fake LLM for end-to-end tests — replies come from an EDN script,
   no endpoint is ever called.

   Active when XI_FAKE_LLM names a script file: xi.cli then installs this
   provider under EVERY provider id, so side turns (titles, summaries,
   compaction), which ask for :anthropic by id, are scripted too.

   Script (re-read every turn):

     {:rules   [{:when  {:prompt \"substr\" :re \"regex\" :model \"substr\"
                         :provider :anthropic :system \"substr\" :user \"bob\"
                         :tool \"advertised-tool\" :side? true}
                 :reply [step …]}
                …]
      :default [step …]     ; main turns no rule matched
      :side    [step …]}    ; side turns (:no-tools?) no rule matched

   Every :when key present must hold; first match wins. Rules without
   `:side? true` never match side turns, so a rule keyed on the user's words
   doesn't also answer the title turn that quotes them.

   Steps, run in order:
     {:text s}             streamed text
     {:thinking s}         a thinking delta
     {:tool name :args m}  a tool call, run like a real provider's: through
                           the tool policy (rules) and the registry
                           (extensions, holds). A tool that was not advertised
                           this turn fails with \"Unknown tool: name\".
     {:sleep ms}           wait (abortable) — for abort / queue tests
     {:error s}            report a provider error and end the turn
     {:usage m}            token usage merged into the turn's result

   Strings (text, thinking, error, and string leaves of :args) expand
   {{tool-result}} (the last tool result), {{prompt}}, {{cwd}} and {{user}}.

   Sessions behave like the Claude provider's: main turns are written as a
   Claude-format transcript under the Claude projects dir, keyed by a session
   id reported via :on-session, so `--session` resumes see the earlier turns.

   With XI_FAKE_LLM_LOG set, every turn appends one JSON line describing what
   the model saw and did: provider, model, prompt, system, advertised tools,
   prior transcript + host history, tool calls with their results, and the
   stop reason."
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [xi.agent :as agent]
            [xi.session :as session]
            [xi.session.sync :as sync]
            [xi.tools.registry :as tools]
            ["node:fs" :as fs]
            ["node:path" :as node-path]))

(defn script-path
  "The script file XI_FAKE_LLM names, or nil when the fake is off."
  []
  (not-empty (aget js/process.env "XI_FAKE_LLM")))

;; ── Script (pure) ────────────────────────────────────────────────────────────

(defn parse-script
  "Parse script EDN text. Throws with a readable message when it isn't a map
   with a :rules vector (or none)."
  [text]
  (let [script (reader/read-string text)]
    (when-not (map? script)
      (throw (js/Error. "script must be a map")))
    (when-not (or (nil? (:rules script)) (sequential? (:rules script)))
      (throw (js/Error. ":rules must be a vector")))
    script))

(defn- rule-matches? [{:keys [prompt re model provider system user tool side?]} req]
  (and (if (some? side?) (= side? (boolean (:side? req))) (not (:side? req)))
       (or (nil? prompt) (str/includes? (str (:prompt req)) prompt))
       (or (nil? re) (boolean (re-find (re-pattern re) (str (:prompt req)))))
       (or (nil? model) (str/includes? (str (:model req)) model))
       (or (nil? provider) (= (keyword provider) (:provider req)))
       (or (nil? system) (str/includes? (str (:system req)) system))
       (or (nil? user) (= user (:user req)))
       (or (nil? tool) (contains? (set (:tools req)) tool))))

(def ^:private fallback-main [{:text "[fake-llm] no rule matched"}])
(def ^:private fallback-side [{:text "Fake chat"}])

(defn select-reply
  "The reply for request `req` ({:prompt :model :provider :system :user
   :tools :side?}): {:rule <index | :default | :side> :steps [step …]}."
  [script req]
  (or (first (keep-indexed (fn [i {conditions :when reply :reply}]
                             (when (rule-matches? conditions req)
                               {:rule i :steps (vec reply)}))
                           (:rules script)))
      (if (:side? req)
        {:rule :side :steps (or (:side script) fallback-side)}
        {:rule :default :steps (or (:default script) fallback-main)})))

(defn expand
  "Expand {{var}} placeholders in `s` from `vars` (keyword → value);
   unknown placeholders stay as they are."
  [s vars]
  (str/replace (str s) #"\{\{([a-z-]+)\}\}"
               (fn [[whole k]]
                 (let [v (get vars (keyword k) ::none)]
                   (if (= ::none v) whole (str v))))))

(defn- expand-args [args vars]
  (walk/postwalk #(if (string? %) (expand % vars) %) args))

;; ── Transcript (Claude CLI format) ───────────────────────────────────────────

(defn- transcript-file [cwd sid]
  (node-path/join (session/claude-projects-dir) (sync/encode-cwd-claude cwd)
                  (str sid ".jsonl")))

(defn transcript-messages
  "A Claude transcript's user/assistant entries as [{:role :text}], one per
   text/tool block — the conversation the model sees on resume."
  [lines]
  (->> lines
       (keep #(try (js->clj (js/JSON.parse %) :keywordize-keys true)
                   (catch :default _ nil)))
       (filter #(contains? #{"user" "assistant"} (:type %)))
       (mapcat (fn [{:keys [type message]}]
                 (let [content (:content message)]
                   (if (string? content)
                     [{:role type :text content}]
                     (keep (fn [{btype :type :as b}]
                             (case btype
                               "text"        {:role type :text (:text b)}
                               "tool_use"    {:role type :tool (:name b) :arguments (:input b)}
                               "tool_result" {:role type :tool-result (:content b)
                                              :is-error (boolean (:is_error b))}
                               nil))
                           content)))))
       vec))

(defn- read-transcript [file]
  (when (fs/existsSync file)
    (transcript-messages (str/split-lines (fs/readFileSync file "utf8")))))

(defn- append-transcript! [file sid cwd messages]
  (fs/mkdirSync (node-path/dirname file) #js {:recursive true})
  (fs/appendFileSync
   file
   (apply str (map (fn [m]
                     (str (js/JSON.stringify
                           (clj->js {:type      (:role m)
                                     :sessionId sid
                                     :cwd       cwd
                                     :timestamp (.toISOString (js/Date.))
                                     :message   (cond-> {:role    (:role m)
                                                         :content (:content m)}
                                                  (:stop_reason m)
                                                  (assoc :stop_reason (:stop_reason m)))}))
                          "\n"))
                   messages))))

;; ── Log ──────────────────────────────────────────────────────────────────────

(defn- log! [entry]
  (when-let [f (not-empty (aget js/process.env "XI_FAKE_LLM_LOG"))]
    (try (fs/appendFileSync f (str (js/JSON.stringify (clj->js entry)) "\n"))
         (catch :default e
           (.write js/process.stderr (str "[fake-llm] log write failed: " (.-message e) "\n"))))))

;; ── Turn ─────────────────────────────────────────────────────────────────────

(defn- start-turn!
  [id {:keys [prompt model system cwd no-tools? resume-session-id tool-ctx tool-policy
              on-text on-thinking on-tool-start on-tool-args on-tool-result on-error
              on-session]
       :as opts}]
  (let [cwd        (or cwd (.cwd js/process))
        side?      (boolean no-tools?)
        {:keys [defs registry]} (if side? {:defs [] :registry {}} (tools/resolve-tooling opts))
        tool-names (mapv :name defs)
        user       (:user tool-ctx)
        prior-file (when (and resume-session-id (not side?)) (transcript-file cwd resume-session-id))
        transcript (when prior-file (read-transcript prior-file))
        sid        (if side? nil (or resume-session-id (str (random-uuid))))
        flags      #js {:aborted false :wake nil}
        !turn      (atom {:messages [{:role "user" :content prompt}]
                          :blocks [] :calls [] :usage {:input_tokens 0 :output_tokens 0}})
        req        {:provider id :model model :prompt prompt :system system
                    :user user :tools tool-names :side? side?}
        base-entry (merge req {:cwd cwd :resume-session-id resume-session-id
                               :session-id sid
                               :transcript (or transcript [])
                               :history (some-> (:history opts) agent/history->messages vec)})
        vars       (fn [] {:tool-result (some-> @!turn :calls peek :content)
                           :prompt prompt :cwd cwd :user user})
        flush!     (fn [extra]
                     (swap! !turn (fn [t]
                                    (cond-> (assoc t :blocks [])
                                      (or (seq (:blocks t)) extra)
                                      (update :messages conj
                                              (merge {:role "assistant" :content (:blocks t)}
                                                     extra))))))
        run-tool!  (fn [n {tool :tool args :args}]
                     (let [call-id (str "fake_" n)
                           args    (expand-args (or args {}) (vars))
                           call    {:id call-id :name tool :arguments args}]
                       (swap! !turn update :blocks conj
                              {:type "tool_use" :id call-id :name tool :input args})
                       (when on-tool-start (on-tool-start call))
                       (when on-tool-args (on-tool-args call))
                       (-> (if (some #{tool} tool-names)
                             (tools/execute-call call registry
                                                 (cond-> (assoc tool-ctx :cwd cwd)
                                                   (:client-pid opts)
                                                   (assoc :client-pid (:client-pid opts)))
                                                 tool-policy)
                             (js/Promise.resolve {:content (str "Unknown tool: " tool)
                                                  :is-error true}))
                           (.then (fn [{:keys [content is-error]}]
                                    (when on-tool-result
                                      (on-tool-result {:id call-id :content content
                                                       :is-error is-error}))
                                    (flush! nil)
                                    (swap! !turn
                                           (fn [t]
                                             (-> t
                                                 (update :calls conj {:name tool :arguments args
                                                                      :content content
                                                                      :is-error is-error})
                                                 (update :messages conj
                                                         {:role "user"
                                                          :content [{:type "tool_result"
                                                                     :tool_use_id call-id
                                                                     :content content
                                                                     :is_error is-error}]})))))))))
        sleep!     (fn [ms]
                     (js/Promise.
                      (fn [resolve]
                        (let [t (js/setTimeout resolve ms)]
                          (set! (.-wake flags) (fn [] (js/clearTimeout t) (resolve nil)))))))
        promise
        (js/Promise.
         (fn [resolve]
           (letfn [(finish [stop-reason]
                     (let [aborted? (.-aborted flags)]
                       (flush! (when (= "stop" stop-reason) {:stop_reason "end_turn"}))
                       (when sid
                         (append-transcript! (transcript-file cwd sid) sid cwd
                                             (:messages @!turn)))
                       (log! (assoc base-entry
                                    :rule (:rule @!turn)
                                    :tool-calls (:calls @!turn)
                                    :stop-reason (if aborted? "aborted" stop-reason)))
                       (resolve (cond-> {:usage (:usage @!turn) :model model
                                         :aborted aborted? :done true}
                                  sid (assoc :session-id sid)))))
                   (fail [message]
                     (when on-error (on-error {:type "error" :message message}))
                     (finish "error"))
                   (step [steps n]
                     (if (.-aborted flags)
                       (finish "aborted")
                       (if-let [{:keys [text thinking tool sleep error usage] :as s} (first steps)]
                         (let [continue #(step (rest steps) (inc n))]
                           (cond
                             text     (let [t (expand text (vars))]
                                        ;; consecutive text steps are one
                                        ;; streamed block, as in a real reply
                                        (swap! !turn update :blocks
                                               (fn [bs]
                                                 (if (= "text" (:type (peek bs)))
                                                   (conj (pop bs) (update (peek bs) :text str t))
                                                   (conj bs {:type "text" :text t}))))
                                        (when on-text (on-text t))
                                        (continue))
                             thinking (do (when on-thinking (on-thinking (expand thinking (vars))))
                                          (continue))
                             tool     (-> (run-tool! n s) (.then continue))
                             sleep    (-> (sleep! sleep) (.then continue))
                             error    (fail (expand error (vars)))
                             usage    (do (swap! !turn update :usage merge usage)
                                          (continue))
                             :else    (fail (str "[fake-llm] unknown step: " (pr-str s)))))
                         (finish "stop"))))]
             (cond
               (and prior-file (nil? transcript))
               ;; like the Claude provider: a stored session that is gone
               ;; makes the agent re-run the turn fresh
               (do (log! (assoc base-entry :stop-reason "resume-failed"))
                   (resolve {:resume-failed true}))

               :else
               (let [script (try (parse-script (fs/readFileSync (script-path) "utf8"))
                                 (catch :default e e))]
                 (if (instance? js/Error script)
                   (fail (str "[fake-llm] " (script-path) ": " (.-message script)))
                   (let [{:keys [rule steps]} (select-reply script req)]
                     (when (and sid on-session) (on-session sid))
                     (swap! !turn assoc :rule rule)
                     (step steps 0))))))))]
    {:promise promise
     :abort!  (fn []
                (set! (.-aborted flags) true)
                (when-let [wake (.-wake flags)] (wake)))}))

(defn provider
  "The fake provider, registered under provider id `id`."
  [id]
  {:id id :start-turn! (fn [opts] (start-turn! id opts))})

(defn providers
  "Provider id → fake provider for every id in `ids` (order kept)."
  [ids]
  (into (array-map) (map (juxt identity provider)) ids))
