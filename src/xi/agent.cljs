(ns xi.agent
  "Agent turn orchestration — pure event handlers + provider effects.

   Flow:
     :prompt/submit ─► history append + busy + [:provider/start-turn …]
     provider streams ─► :agent/text-delta | :agent/thinking-delta |
                         :agent/tool-start | :agent/tool-args |
                         :agent/tool-result | :agent/error
     provider resolves ─► :agent/turn-end (busy off, queued prompt drained)
     :agent/abort ─► [:provider/abort …] (turn-end follows with :aborted? true)

   History entries (room :history) are flat maps:
     {:kind :user      :text :images}
     {:kind :text      :text :done?}          ;; assistant text, folded deltas
     {:kind :thinking  :text :done?}
     {:kind :tool-call :id :tool :arguments :result :is-error :status}
     {:kind :error     :error}

   Providers are maps {:id kw :start-turn! (fn [opts] {:promise :abort!})}
   using the callback opts built here — they never touch app state.
   All handlers are pure; `create-fx` is the contained impure edge (holds
   in-flight turn handles, which are runtime resources, not app state)."
  (:require [clojure.string :as str]
            [xi.core.state :as state]
            [xi.util :as util]))

;; ── History folding (pure) ───────────────────────────────────────────────────

(defn- fold-delta
  "Extend the trailing open entry of `kind`, or start a new one."
  [history kind text]
  (let [last-entry (peek history)]
    (if (and (= kind (:kind last-entry)) (not (:done? last-entry)))
      (conj (pop history) (update last-entry :text str text))
      (conj history {:kind kind :text text}))))

(defn- update-tool-call
  "Update the most recent tool-call entry with matching id."
  [history id f]
  (if-let [idx (->> (range (dec (count history)) -1 -1)
                    (filter #(and (= :tool-call (:kind (history %)))
                                  (= id (:id (history %)))))
                    first)]
    (update history idx f)
    history))

(defn- finalize-history
  "Mark all open streaming entries as done. A tool call still :running when the
   turn ends (interrupted before its result arrived) is settled to :aborted so
   its spinner stops instead of spinning forever."
  [history]
  (mapv (fn [entry]
          (cond
            (and (#{:text :thinking} (:kind entry)) (not (:done? entry)))
            (assoc entry :done? true)

            (and (= :tool-call (:kind entry)) (= :running (:status entry)))
            (assoc entry :status :aborted)

            :else entry))
        history))

;; ── Turn construction (pure) ─────────────────────────────────────────────────

(defn history->context
  "Render history's user/assistant text exchanges as a <conversation_history>
   block for system-prompt injection, or nil when there is nothing to carry.
   Used after /tree navigation: the provider session is fresh, so the
   truncated conversation rides along as context."
  [history]
  (let [msgs (keep (fn [{:keys [kind text]}]
                     (when (seq text)
                       (case kind
                         :user (str "user: " text)
                         :text (str "assistant: " text)
                         nil)))
                   history)]
    (when (seq msgs)
      (str "<conversation_history>\n"
           "The user rewound this conversation to an earlier point. "
           "Everything below already happened; continue from here.\n\n"
           (str/join "\n\n" msgs)
           "\n</conversation_history>"))))

(defn- prompt-with-image-paths
  "Append the on-disk paths of attached images to the provider prompt so the
   agent's file tools and subagents can reach them. Kept out of the history
   entry text so the user's message bubble stays clean."
  [prompt images]
  (let [paths (keep :path images)]
    (if (seq paths)
      (str/join "\n" (concat (some-> prompt str/trim not-empty vector)
                             (map #(str "[Attached image: " % "]") paths)))
      prompt)))

(defn- build-turn-effect
  "Build the :provider/start-turn effect payload. When :resume-id is nil and
   :context-history is supplied, the prior conversation is rendered into the
   system prompt so the fresh provider session keeps the context."
  [{:keys [room resume-id prompt images context-history]}]
  (let [agent   (:agent room)
        context (when (and (nil? resume-id) context-history)
                  (history->context context-history))
        system  (if context
                  (if-let [base (:system agent)]
                    (str base "\n\n" context)
                    context)
                  (:system agent))]
    [:provider/start-turn
     (cond-> {:room-id  (:id room)
              :prompt   (prompt-with-image-paths prompt images)
              :model    (:model agent)
              :provider (:provider agent)
              :cwd      (:cwd room)
              :effort   (:effort agent)
              :system   system
              :personal-agent? (:personal-agent? agent)}
       (seq images) (assoc :images images)
       resume-id    (assoc :resume-session-id resume-id))]))

(defn- start-turn-effect
  "Build the :provider/start-turn effect payload from room state + prompt.
   When the session is flagged :inject-history? and has no provider session
   to resume (i.e. after /tree navigation), the truncated history is rendered
   into the system prompt so the fresh provider session keeps the context."
  [room {:keys [text images]}]
  (let [resume-id (get-in room [:session :provider-session-id])]
    (build-turn-effect
     {:room room
      :resume-id resume-id
      :prompt text
      :images images
      :context-history (when (and (nil? resume-id)
                                  (get-in room [:session :inject-history?]))
                         (:history room))})))

(defn- begin-turn [st room prompt]
  ;; A live turn diverges the room's history from its resumed on-disk snapshot,
  ;; so the cached :msg-hash/:msg-count (set at resume time) no longer describe
  ;; the current history. Clear them so the web cache doesn't pair grown history
  ;; with a stale hash — which would make the incremental-resume prefix check
  ;; double-append the tail. The next resume event re-establishes them.
  {:state   (-> st
                (update-in [:rooms (:id room) :history] conj
                           {:kind :user :text (:text prompt) :images (:images prompt)})
                (update-in [:rooms (:id room)] dissoc :msg-hash :msg-count)
                (assoc-in [:rooms (:id room) :agent :busy?] true))
   :effects [(start-turn-effect room prompt)]})

(defn- join-prompts
  "Combine queued prompts into a single prompt: non-blank texts joined by
   blank lines, images concatenated in order."
  [prompts]
  {:text   (->> (map :text prompts)
                (remove str/blank?)
                (str/join "\n\n"))
   :images (into [] (mapcat :images) prompts)})

;; ── Event handlers (pure) ────────────────────────────────────────────────────

(defn- prompt-submit [st {:keys [room-id text images]}]
  (when-let [room (state/get-room st room-id)]
    (let [prompt {:text text :images images}]
      (if (get-in room [:agent :busy?])
        ;; Busy — queue for after the current turn settles
        {:state (update-in st [:rooms room-id :agent :queued] (fnil conj []) prompt)}
        (begin-turn st room prompt)))))

(defn- text-delta [st {:keys [room-id text]}]
  (when (state/get-room st room-id)
    {:state (update-in st [:rooms room-id :history] fold-delta :text text)}))

(defn- thinking-delta [st {:keys [room-id text]}]
  (when (state/get-room st room-id)
    {:state (update-in st [:rooms room-id :history] fold-delta :thinking text)}))

(defn- tool-start [st {:keys [room-id id tool arguments]}]
  (when (state/get-room st room-id)
    {:state (update-in st [:rooms room-id :history] conj
                       {:kind :tool-call :id id :tool tool
                        :arguments arguments :status :running})}))

(defn- tool-args [st {:keys [room-id id arguments]}]
  (when (state/get-room st room-id)
    {:state (update-in st [:rooms room-id :history] update-tool-call id
                       #(assoc % :arguments arguments))}))

(defn- tool-result [st {:keys [room-id id content is-error]}]
  (when (state/get-room st room-id)
    {:state (update-in st [:rooms room-id :history] update-tool-call id
                       #(assoc % :result content
                               :is-error (boolean is-error)
                               :status (if is-error :error :done)))}))

(defn- agent-error [st {:keys [room-id error]}]
  (when (state/get-room st room-id)
    {:state (update-in st [:rooms room-id :history] conj
                       {:kind :error :error error})}))

(defn- turn-end [st {:keys [room-id usage cost provider-session-id aborted?]}]
  (when-let [room (state/get-room st room-id)]
    (let [queued (get-in room [:agent :queued])
          st' (-> st
                  (update-in [:rooms room-id :history] finalize-history)
                  (update-in [:rooms room-id :agent]
                             #(-> %
                                  (assoc :busy? false :queued [])
                                  (cond->
                                   usage (assoc :last-usage usage)
                                   cost  (assoc :last-cost cost))))
                  (cond->
                   provider-session-id
                    (assoc-in [:rooms room-id :session :provider-session-id]
                              provider-session-id)
                   aborted?
                    (update-in [:rooms room-id :history] conj {:kind :aborted})))]
      ;; Drain the whole queue as one combined prompt by re-dispatching —
      ;; keeps a single submission code path.
      (cond-> {:state st'}
        (seq queued)
        (assoc :effects [[:app/dispatch (merge {:type :prompt/submit :room-id room-id}
                                               (join-prompts queued))]])))))

(defn- retry-fresh
  "The provider could not start (a dead resume session, or a missing working
   directory that has since been fixed). Drop the dead id and re-run the
   pending turn as a fresh session, carrying the in-memory history as context
   so nothing is lost. The pending prompt is the last :user history entry (no
   assistant output was produced before the failure); we replay it as the
   fresh prompt and inject everything before it as context. Scanning for the
   last :user entry — rather than assuming it's the trailing one — tolerates a
   transient entry appended after it (e.g. the 'CWD changed' status line the
   cwd-recovery flow emits)."
  [st {:keys [room-id]}]
  (when-let [room (state/get-room st room-id)]
    (let [history (:history room)
          idx     (->> (map-indexed vector history)
                       (filter #(= :user (:kind (second %))))
                       (map first)
                       last)]
      (when idx
        (let [user-ent (nth history idx)
              room'    (assoc-in room [:session :provider-session-id] nil)]
          {:state   (assoc-in st [:rooms room-id :session :provider-session-id] nil)
           :effects [(build-turn-effect
                      {:room room'
                       :resume-id nil
                       :prompt (:text user-ent)
                       :images (:images user-ent)
                       :context-history (subvec (vec history) 0 idx)})]})))))

(defn- session-init
  "Provider reported its session id early (e.g. Claude system/init).
   Store it so lobby-payload can filter duplicate external sessions."
  [st {:keys [room-id provider-session-id]}]
  (when (and (state/get-room st room-id) provider-session-id)
    {:state (assoc-in st [:rooms room-id :session :provider-session-id]
                      provider-session-id)}))

(defn- agent-abort [st {:keys [room-id]}]
  (when (get-in st [:rooms room-id :agent :busy?])
    {:effects [[:provider/abort {:room-id room-id}]]}))

(defn- queue-remove
  "Drop the queued prompt at `index` (a message the user queued while the
   agent was busy but no longer wants to send)."
  [st {:keys [room-id index]}]
  (when-let [room (state/get-room st room-id)]
    (let [queued (vec (get-in room [:agent :queued]))]
      (when (< -1 index (count queued))
        {:state (assoc-in st [:rooms room-id :agent :queued]
                          (into (subvec queued 0 index)
                                (subvec queued (inc index))))}))))

(def handlers
  {:prompt/submit        prompt-submit
   :agent/text-delta     text-delta
   :agent/thinking-delta thinking-delta
   :agent/tool-start     tool-start
   :agent/tool-args      tool-args
   :agent/tool-result    tool-result
   :agent/error          agent-error
   :agent/session-init   session-init
   :agent/turn-end       turn-end
   :agent/retry-fresh    retry-fresh
   :agent/abort          agent-abort
   :prompt/queue-remove  queue-remove})

;; ── Provider routing (pure) ──────────────────────────────────────────────────

(defn resolve-provider
  "Pick a provider: explicit :provider key wins, else route by model name."
  [providers {:keys [provider model]}]
  (or (get providers provider)
      (get providers (if (util/claude-model? model) :claude :ollama))
      (get providers :claude)
      (first (vals providers))))

;; ── Effects (contained impure edge) ──────────────────────────────────────────

(defn- cwd-select-options
  "Options for the missing-cwd recovery dialog. Concrete dirs carry a string
   value; :custom is a sentinel the client turns into a typed path."
  []
  (let [home (aget js/process.env "HOME")]
    (->> [{:label (str "Xi dir (" (.cwd js/process) ")") :value (.cwd js/process)}
          (when (seq home) {:label (str "Home (" home ")") :value home})
          {:label "Temp (/tmp)" :value "/tmp"}
          {:label "Custom directory…" :value :custom}]
         (remove nil?)
         vec)))

(defn- event-callbacks
  "Provider streaming callbacks → :agent/* event dispatches."
  [dispatch! room-id]
  {:on-text        (fn [text]
                     (dispatch! {:type :agent/text-delta :room-id room-id :text text}))
   :on-thinking    (fn [text]
                     (dispatch! {:type :agent/thinking-delta :room-id room-id :text text}))
   :on-tool-start  (fn [{:keys [id name arguments]}]
                     (dispatch! {:type :agent/tool-start :room-id room-id
                                 :id id :tool name :arguments arguments}))
   :on-tool-args   (fn [{:keys [id arguments]}]
                     (dispatch! {:type :agent/tool-args :room-id room-id
                                 :id id :arguments arguments}))
   :on-tool-result (fn [{:keys [id content is-error]}]
                     (dispatch! {:type :agent/tool-result :room-id room-id
                                 :id id :content content :is-error is-error}))
   :on-session     (fn [session-id]
                     (dispatch! {:type :agent/session-init :room-id room-id
                                 :provider-session-id session-id}))
   :on-error       (fn [error]
                     (dispatch! {:type :agent/error :room-id room-id :error error}))})


(defn- room-client-pid
  "Pid of the client driving `room-id` (preferring a TUI client), or nil, from
   the connection registry. Threaded into the tool ctx so chrome-mcp can scope
   to that client's terminal workspace (see xi.ext.chrome-guard)."
  [st room-id]
  (->> (vals (get-in st [:connection :clients]))
       (filter (fn [c] (and (= room-id (:room-id c)) (:pid c))))
       (sort-by (fn [c] (if (= "tui" (:platform c)) 0 1)))
       first
       :pid))

(defn create-fx
  "Provider effects. `providers` is a map of provider-id → provider.
   In-flight turn handles live here — runtime resources, not app state.

   Second arity threads extension tooling into every turn:
     :tool-gate              composed 2-arg gate (fn [tool-call ctx] → Promise);
                             wrapped here into the provider's single-arg gate,
                             closing over a per-turn ctx so extensions can
                             dispatch, read state, confirm via dialogs, etc.
     :extra-tool-definitions extra tool defs exposed to the provider
     :extra-tool-registry    name → exec-fn for those extra tools
     :ask!                   dialog ask! — partially applied into the gate
                             ctx as :confirm! (fn [message] → Promise<bool>)"
  ([providers] (create-fx providers nil))
  ([providers {:keys [tool-gate extra-tool-definitions extra-tool-registry ask!]}]
  (let [inflight (js/Map.)]
    {:provider/start-turn
     (fn [{:keys [dispatch! get-state]} {:keys [room-id cwd] :as payload}]
       (let [provider (resolve-provider providers payload)
             ;; Per-turn context handed to extension tool gates: lets a gate
             ;; read live state, dispatch events, and raise confirm dialogs.
             gate-ctx {:dispatch! dispatch!
                       :get-state get-state
                       :room-id   room-id
                       :cwd       cwd
                       ;; Raise a confirm dialog and resolve to the answer.
                       ;; ask! resolves to a safe default (false) when no
                       ;; client is attached (see xi.ext.core/create-dialogs).
                       ;; With {:allow-always? true} the dialog offers a third
                       ;; option that resolves to :always (allow + remember).
                       :confirm!  (when ask!
                                    (fn confirm!
                                      ([message] (confirm! message nil))
                                      ([message opts]
                                       (ask! {:dispatch! dispatch! :state (get-state)}
                                             {:room-id room-id
                                              :dialog  (cond-> {:type :confirm :message message}
                                                         (:allow-always? opts) (assoc :allow-always? true))}))))}
             gate1 (when tool-gate
                     (fn [tool-call] (tool-gate tool-call gate-ctx)))
             ;; The turn's cwd doesn't exist on this host (e.g. a Pi session
             ;; with cwd=/var/lib/xi opened elsewhere). Ask the user where to
             ;; run, persist it on the room, then replay the turn fresh.
             recover-cwd!
             (fn [missing-cwd]
               (if ask!
                 (-> (ask! {:dispatch! dispatch! :state (get-state)}
                           {:room-id room-id
                            :dialog  {:type    :cwd-select
                                      :message (str "This chat's working directory "
                                                    "doesn't exist on this machine: "
                                                    missing-cwd
                                                    ". Choose where it should run:")
                                      :options (cwd-select-options)}})
                     (.then
                      (fn [chosen]
                        (if (and (string? chosen) (pos? (count chosen)))
                          (do (dispatch! {:type :cwd/changed :room-id room-id
                                          :cwd chosen})
                              (dispatch! {:type :agent/retry-fresh
                                          :room-id room-id}))
                          (do (dispatch! {:type :ui/status :room-id room-id
                                          :text "Cancelled — no working directory set."})
                              (dispatch! {:type :agent/turn-end
                                          :room-id room-id}))))))
                 (do (dispatch! {:type :agent/error :room-id room-id
                                 :error {:type "error"
                                         :message (str "Working directory does not exist: "
                                                       missing-cwd)}})
                     (dispatch! {:type :agent/turn-end :room-id room-id}))))
             client-pid (room-client-pid (get-state) room-id)
             {:keys [promise abort!]}
             ((:start-turn! provider)
              (cond-> (merge payload (event-callbacks dispatch! room-id))
                gate1                  (assoc :tool-gate gate1)
                extra-tool-definitions (assoc :extra-tool-definitions extra-tool-definitions)
                extra-tool-registry    (assoc :extra-tool-registry extra-tool-registry)
                client-pid             (assoc :client-pid client-pid)))]
         (.set inflight room-id {:abort! abort!})
         (-> promise
             (.then
              (fn [result]
                (.delete inflight room-id)
                (cond
                  ;; Stored provider session was gone; re-run this turn fresh
                  ;; instead of ending it with a dead-end error.
                  (:resume-failed result)
                  (dispatch! {:type :agent/retry-fresh :room-id room-id})

                  ;; Working directory vanished; ask the user for a new one.
                  (:cwd-missing result)
                  (recover-cwd! cwd)

                  :else
                  (dispatch! {:type :agent/turn-end
                              :room-id room-id
                              :usage (:usage result)
                              :cost (:cost result)
                              :provider-session-id (:session-id result)
                              :aborted? (boolean (:aborted result))}))))
             (.catch
              (fn [err]
                (.delete inflight room-id)
                (dispatch! {:type :agent/error :room-id room-id
                            :error {:type "error" :message (str (.-message err))}})
                (dispatch! {:type :agent/turn-end :room-id room-id}))))))

     :provider/abort
     (fn [_ {:keys [room-id]}]
       (when-let [handle (.get inflight room-id)]
         ((:abort! handle))))})))