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
  (:require [xi.core.state :as state]
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
  "Mark all open streaming entries as done."
  [history]
  (mapv (fn [entry]
          (if (and (#{:text :thinking} (:kind entry)) (not (:done? entry)))
            (assoc entry :done? true)
            entry))
        history))

;; ── Turn construction (pure) ─────────────────────────────────────────────────

(defn- start-turn-effect
  "Build the :provider/start-turn effect payload from room state + prompt."
  [room {:keys [text images]}]
  (let [agent (:agent room)]
    [:provider/start-turn
     (cond-> {:room-id  (:id room)
              :prompt   text
              :model    (:model agent)
              :provider (:provider agent)
              :cwd      (:cwd room)
              :effort   (:effort agent)
              :system   (:system agent)
              :personal-agent? (:personal-agent? agent)}
       (seq images)
       (assoc :images images)

       (get-in room [:session :provider-session-id])
       (assoc :resume-session-id (get-in room [:session :provider-session-id])))]))

(defn- begin-turn [st room prompt]
  {:state   (-> st
                (update-in [:rooms (:id room) :history] conj
                           {:kind :user :text (:text prompt) :images (:images prompt)})
                (assoc-in [:rooms (:id room) :agent :busy?] true))
   :effects [(start-turn-effect room prompt)]})

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
    (let [[next-prompt & rest-queued] (get-in room [:agent :queued])
          st' (-> st
                  (update-in [:rooms room-id :history] finalize-history)
                  (update-in [:rooms room-id :agent]
                             #(-> %
                                  (assoc :busy? false :queued (vec rest-queued))
                                  (cond->
                                   usage (assoc :last-usage usage)
                                   cost  (assoc :last-cost cost))))
                  (cond->
                   provider-session-id
                    (assoc-in [:rooms room-id :session :provider-session-id]
                              provider-session-id)
                   aborted?
                    (update-in [:rooms room-id :history] conj {:kind :aborted})))]
      ;; Drain one queued prompt by re-dispatching — keeps a single code path.
      (cond-> {:state st'}
        next-prompt
        (assoc :effects [[:app/dispatch (merge {:type :prompt/submit :room-id room-id}
                                               next-prompt)]])))))

(defn- agent-abort [st {:keys [room-id]}]
  (when (get-in st [:rooms room-id :agent :busy?])
    {:effects [[:provider/abort {:room-id room-id}]]}))

(def handlers
  {:prompt/submit        prompt-submit
   :agent/text-delta     text-delta
   :agent/thinking-delta thinking-delta
   :agent/tool-start     tool-start
   :agent/tool-args      tool-args
   :agent/tool-result    tool-result
   :agent/error          agent-error
   :agent/turn-end       turn-end
   :agent/abort          agent-abort})

;; ── Provider routing (pure) ──────────────────────────────────────────────────

(defn resolve-provider
  "Pick a provider: explicit :provider key wins, else route by model name."
  [providers {:keys [provider model]}]
  (or (get providers provider)
      (get providers (if (util/claude-model? model) :claude :ollama))
      (get providers :claude)
      (first (vals providers))))

;; ── Effects (contained impure edge) ──────────────────────────────────────────

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
   :on-error       (fn [error]
                     (dispatch! {:type :agent/error :room-id room-id :error error}))})

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
                       :confirm!  (when ask!
                                    (fn [message]
                                      (ask! {:dispatch! dispatch! :state (get-state)}
                                            {:room-id room-id
                                             :dialog  {:type :confirm :message message}})))}
             gate1 (when tool-gate
                     (fn [tool-call] (tool-gate tool-call gate-ctx)))
             {:keys [promise abort!]}
             ((:start-turn! provider)
              (cond-> (merge payload (event-callbacks dispatch! room-id))
                gate1                  (assoc :tool-gate gate1)
                extra-tool-definitions (assoc :extra-tool-definitions extra-tool-definitions)
                extra-tool-registry    (assoc :extra-tool-registry extra-tool-registry)))]
         (.set inflight room-id {:abort! abort!})
         (-> promise
             (.then
              (fn [result]
                (.delete inflight room-id)
                (dispatch! {:type :agent/turn-end
                            :room-id room-id
                            :usage (:usage result)
                            :cost (:cost result)
                            :provider-session-id (:session-id result)
                            :aborted? (boolean (:aborted result))})))
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
