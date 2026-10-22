(ns xi.providers.process
  "A provider backed by an external program (docs/provider-protocol.md):
   `create` turns a manifest (xi.providers.manifest) into the provider map
   xi.agent and the side-turn effects consume —
   {:id :start-turn! :list-models! :login :usage-sources :store :spec …}.

   Every request spawns the program afresh (xi.providers.runner), so an edit
   to it applies on the next turn. Xi keeps tools, rules and dialogs: a
   `tool-call` frame runs through the turn's tool policy and registry here,
   and the program only sees the result."
  (:require [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as node-path]
            [xi.agent :as agent]
            [xi.providers.locks :as locks]
            [xi.providers.manifest :as manifest]
            [xi.providers.runner :as runner]
            [xi.session.claude-store :as claude-store]
            [xi.system-prompt :as system-prompt]
            [xi.tools.registry :as tools]
            [xi.util :as util]))

(def protocol-version 1)

(defn data-dir
  "The directory a provider owns: $XDG_DATA_HOME (else ~/.local/share)
   /xi/providers/<id>."
  [id]
  (node-path/join (or (not-empty (aget js/process.env "XDG_DATA_HOME"))
                      (node-path/join (os/homedir) ".local" "share"))
                  "xi" "providers" (name id)))

;; ── Tool calls ───────────────────────────────────────────────────────────────

(defn run-call
  "Run one proxied tool call through `tool-policy` (nil lets it through, a
   nil result blocks, {:intercepted true :result …} stands in for the tool's
   result) and the turn's `registry`. Promise of {:content [block …]
   :is-error bool}."
  [{:keys [name arguments]} {:keys [registry tool-policy tool-ctx cwd client-pid]}]
  (let [policy (or tool-policy (fn [call] (js/Promise.resolve call)))
        call   {:name name :arguments arguments}]
    (-> (policy call)
        (.then
         (fn [gated]
           (cond
             (nil? gated)
             {:content [{:type "text" :text "Blocked by Xi permission gate"}] :is-error true}

             (:intercepted gated)
             {:content  (util/cap-tool-result-content (get-in gated [:result :content]))
              :is-error (boolean (get-in gated [:result :is-error]))}

             :else
             (if-let [exec-fn (get registry name)]
               (tools/run-tool exec-fn (or (:arguments gated) arguments)
                               (assoc tool-ctx :cwd cwd :client-pid client-pid) call)
               {:content [{:type "text" :text (str "Unknown tool: " name)}] :is-error true}))))
        (.catch (fn [e]
                  {:content [{:type "text" :text (str "Tool error: " (.-message e))}] :is-error true})))))

;; ── Requests ─────────────────────────────────────────────────────────────────

(defn- camel [k]
  (let [[h & t] (str/split (name k) #"-")]
    (str/replace (apply str h (map str/capitalize t)) #"\?$" "")))

(defn camel-keys
  "A config map's keyword keys as the camelCase strings a program reads."
  [m]
  (into {} (map (fn [[k v]] [(if (keyword? k) (camel k) k) v])) m))

(defn- base-request [{:keys [id]} options type]
  {:type       type
   :protocol   protocol-version
   :providerId (name id)
   :options    (camel-keys options)
   :dataDir    (data-dir id)})

(defn- handle-lock!
  "The protocol's host services common to every request."
  [frame owner send! log-tag]
  (case (:type frame)
    "lock"   (-> (locks/acquire! (:name frame) owner)
                 (.then (fn [_] (send! {:type "locked" :name (:name frame)}))))
    "unlock" (locks/release! (:name frame) owner)
    "log"    (js/console.error (str "[" log-tag "] " (:message frame)))
    nil))

(defn- exit-message [{:keys [label]} {:keys [code signal error stderr]}]
  (str label " provider "
       (cond error (str "could not start: " error)
             signal (str "was killed (" signal ")")
             :else (str "exited (code " code ") without finishing"))
       (when (seq (str/trim (str stderr)))
         (str ": " (str/trim (last (str/split-lines (str/trim stderr))))))))

(defn request!
  "Run one non-turn request (`models`, `usage`, `login`, `logout`). Promise of
   the `done` frame; rejects on an `error` frame or a crash. `on-frame` sees
   every other frame."
  [m options type & [{:keys [on-frame]}]]
  (js/Promise.
   (fn [resolve reject]
     (let [owner   #js {}
           log-tag (str "provider " (name (:id m)))
           error   (atom nil)
           settled (atom false)
           h       (atom nil)
           frame!  (fn [frame]
                     (case (:type frame)
                       "done"  (do (reset! settled true)
                                   (if @error (reject (js/Error. @error)) (resolve frame)))
                       "error" (reset! error (or (get-in frame [:error :message]) "provider error"))
                       (do (handle-lock! frame owner (:send! @h) log-tag)
                           (when on-frame (on-frame frame)))))]
       (reset! h (runner/start! {:command (:command m) :dir (:dir m) :log-tag log-tag
                                 :request (base-request m options type)
                                 :on-frame frame!}))
       (.then (:exited @h)
              (fn [exit]
                (locks/release-all! owner)
                (when-not @settled
                  (reject (js/Error. (or @error (exit-message m exit)))))))))))

;; ── Turns ────────────────────────────────────────────────────────────────────

(defn- cwd-missing?
  "A working directory that doesn't exist on this host (a session opened on
   another machine): the turn can't run, xi.agent asks for another."
  [cwd]
  (and (string? cwd) (seq cwd)
       (try (not (fs/existsSync cwd)) (catch :default _ false))))

(defn- history-messages
  "xi.agent's neutral history in the protocol's camelCase shape."
  [history]
  (mapv (fn [{:keys [role text tool-calls results]}]
          (case role
            :user      {:role "user" :text text}
            :assistant (cond-> {:role "assistant" :text text}
                         (seq tool-calls) (assoc :toolCalls tool-calls))
            :tool      {:role "tool"
                        :results (mapv (fn [{:keys [id content is-error]}]
                                         {:id id :content (str content) :isError (boolean is-error)})
                                       results)}))
        (agent/history->messages history)))

(defn turn-request
  "The `turn` request for xi.agent's start-turn! `opts`, with the turn's
   resolved tool `defs`."
  [m options defs {:keys [model prompt images system cwd effort reasoning-effort
                          no-tools? no-thinking? room-id env history resume-session-id]}]
  (let [cwd   (or cwd (.cwd js/process))
        self? (= :self (:transcripts m))]
    (cond-> (assoc (base-request m options "turn")
                   :model  model
                   :prompt (or prompt "")
                   :cwd    cwd
                   :tools  (if no-tools? [] defs))
      ;; A file resumed from a transcript has no data, only the path the
      ;; prompt names (xi.agent/prompt-with-attachment-paths).
      (some :data images) (assoc :images (into [] (comp (filter :data)
                                                        (map (fn [{:keys [media-type data]}]
                                                               {:mediaType media-type :data data})))
                                               images))
      system             (assoc :system system)
      (and (:base-prompt? m) (not no-tools?))
      (assoc :basePrompt (system-prompt/build defs cwd))
      (and (not self?) (seq history)) (assoc :history (history-messages history))
      (and self? resume-session-id)   (assoc :sessionId resume-session-id)
      effort             (assoc :effort effort)
      reasoning-effort   (assoc :reasoningEffort reasoning-effort)
      no-tools?          (assoc :noTools true)
      no-thinking?       (assoc :noThinking true)
      room-id            (assoc :roomId (if (keyword? room-id) (name room-id) (str room-id)))
      (seq env)          (assoc :env env))))

(defn done->result
  "The `done` frame as the result map xi.agent and the side turns read."
  [{:keys [usage cost sessionId stopReason resultText isError aborted model failure]}]
  (cond-> {:usage       (or usage {})
           :stop-reason stopReason
           :aborted     (boolean aborted)
           :is-error    (boolean isError)
           :done        true}
    cost                       (assoc :cost cost)
    sessionId                  (assoc :session-id sessionId)
    resultText                 (assoc :result-text resultText)
    model                      (assoc :model model)
    (= "resume-failed" failure) (assoc :resume-failed true)
    (= "fatal" failure)        (assoc :fatal-error true)))

(defn start-turn!
  "Run one turn of the provider in manifest `m`. Returns {:promise :abort!
   :exited}; the promise resolves (never rejects) with the result map."
  [m options opts]
  (let [cwd (or (:cwd opts) (.cwd js/process))]
    (if (cwd-missing? cwd)
      (let [p (js/Promise.resolve {:cwd-missing true :done true :usage {}})]
        {:promise p :exited p :abort! (fn [] nil)})
      (let [{:keys [on-text on-thinking on-tool-start on-tool-args on-tool-result
                    on-session on-error]} opts
            {:keys [defs registry]} (tools/resolve-tooling opts)
            owner    #js {}
            log-tag  (str "provider " (name (:id m)))
            aborted? (atom false)
            done     (atom nil)
            h        (atom nil)
            dispatch {:registry    registry
                      :tool-policy (:tool-policy opts)
                      :tool-ctx    (:tool-ctx opts)
                      :cwd         cwd
                      :client-pid  (:client-pid opts)}
            frame!   (fn [{:keys [type] :as frame}]
                       (when-not @done
                         (case type
                           "text"       (when on-text (on-text (:text frame)))
                           "thinking"   (when on-thinking (on-thinking (:text frame)))
                           "tool-start" (when on-tool-start
                                          (on-tool-start {:id (:id frame) :name (:name frame)
                                                          :arguments (or (:arguments frame) {})}))
                           "tool-args"  (when on-tool-args
                                          (on-tool-args (select-keys frame [:id :name :arguments])))
                           "tool-done"  (when on-tool-result
                                          (on-tool-result {:id (:id frame) :content (:content frame)
                                                           :is-error (boolean (:isError frame))}))
                           "session"    (when on-session (on-session (:sessionId frame)))
                           "error"      (when on-error (on-error (:error frame)))
                           "tool-call"  (-> (run-call frame dispatch)
                                            (.then (fn [{:keys [content is-error]}]
                                                     ((:send! @h) {:type "tool-result" :id (:id frame)
                                                                   :content content :isError (boolean is-error)}))))
                           "done"       (do (reset! done (done->result frame))
                                            ;; the program exits on its own; SIGTERM makes sure
                                            ((:kill! @h)))
                           (handle-lock! frame owner (:send! @h) log-tag))))]
        (reset! h (runner/start! {:command (:command m) :dir (:dir m) :log-tag log-tag
                                  :request (turn-request m options defs opts)
                                  :on-frame frame!}))
        (let [exited  (.then (:exited @h) (fn [exit] (locks/release-all! owner) exit))
              promise (.then exited
                             (fn [exit]
                               (or @done
                                   (do (when (and on-error (not @aborted?))
                                         (on-error {:type "error" :message (exit-message m exit)}))
                                       {:usage {} :done true :is-error (not @aborted?)
                                        :aborted @aborted? :stop-reason (if @aborted? "aborted" "error")}))))]
          {:promise promise
           :exited  exited
           :abort!  (fn []
                      (reset! aborted? true)
                      ((:send! @h) {:type "abort"})
                      (js/setTimeout (:kill! @h) 500))})))))

;; ── Readings ─────────────────────────────────────────────────────────────────

(defn- kebab [k]
  (keyword (str/lower-case (str/replace (name k) #"([a-z0-9])([A-Z])" "$1-$2"))))

(defn reading
  "A usage reading from a program (camelCase keys) as xi.usage's
   kebab-keyed reading; :provider and badge tones become keywords."
  [r]
  (let [kebabed (fn kebabed [x]
                  (cond (map? x)        (into {} (map (fn [[k v]] [(kebab k) (kebabed v)])) x)
                        (sequential? x) (mapv kebabed x)
                        :else x))]
    (-> (kebabed r)
        (update :provider keyword)
        (update :badges (fn [bs] (some->> bs (mapv #(update % :tone keyword))))))))

;; ── The provider map ─────────────────────────────────────────────────────────

(defn create
  "The provider for manifest `m` with config `options` (the config.edn entry's
   own keys)."
  [m options]
  (let [{:keys [id label capabilities models login transcripts]} m]
    (cond-> {:id           id
             :label        label
             :manifest     m
             :spec         (manifest/spec m)
             :side-turns?  (:side-turns? m)
             :start-turn!  (fn [opts] (start-turn! m options opts))
             :list-models! (fn []
                             (cond
                               (seq models) (js/Promise.resolve models)
                               (contains? capabilities :models)
                               (-> (request! m options "models")
                                   (.then (fn [{:keys [ids]}] (vec (filter string? ids))))
                                   (.catch (fn [e]
                                             (js/console.error (str "[provider " (name id) "] models: " (.-message e)))
                                             [])))
                               :else (js/Promise.resolve [])))}
      ;; the program writes Claude CLI transcripts itself; rewinds cut them
      (= :self transcripts)
      (assoc :store claude-store/store)

      login
      (assoc :login {:name    (:name login)
                     :label   (:label login)
                     :login!  (fn [{:keys [on-prompt]}]
                                (-> (request! m options "login"
                                              {:on-frame (fn [f]
                                                           (when (and on-prompt (= "login-prompt" (:type f)))
                                                             (on-prompt {:url (:url f) :user-code (:userCode f)})))})
                                    (.then (fn [{:keys [email]}] (cond-> {} email (assoc :email email))))))
                     :logout! (fn [] (request! m options "logout"))})

      (contains? capabilities :usage)
      (assoc :usage-sources
             [{:id    (keyword (str "provider-" (name id)))
               :fetch (fn [_]
                        (-> (request! m options "usage")
                            (.then (fn [{:keys [readings]}] (mapv reading readings)))))}]))))
