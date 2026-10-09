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
     {:kind :tool-call :id :tool :arguments :result :is-error :status
      :started-at}          ;; epoch ms the call began (drives the web run timer)
     {:kind :error     :error}

   Providers are maps {:id kw :start-turn! (fn [opts] {:promise :abort!})}
   using the callback opts built here — they never touch app state.
   All handlers are pure; `create-fx` is the contained impure edge (holds
   in-flight turn handles, which are runtime resources, not app state)."
  (:require [clojure.string :as str]
            [xi.core.state :as state]
            [xi.dialog :as dialog]
            [xi.util :as util]))

;; ── History folding (pure) ───────────────────────────────────────────────────

(defn fold-delta
  "Extend the trailing open entry of `kind`, or start a new one."
  [history kind text]
  (let [last-entry (peek history)]
    (if (and (= kind (:kind last-entry)) (not (:done? last-entry)))
      (conj (pop history) (update last-entry :text str text))
      (conj history {:kind kind :text text}))))

(defn update-tool-call
  [history id f]
  (if-let [idx (->> (range (dec (count history)) -1 -1)
                    (filter #(and (= :tool-call (:kind (history %)))
                                  (= id (:id (history %)))))
                    first)]
    (update history idx f)
    history))

(defn finalize-history
  "Mark all open streaming entries done; a tool call still :running at turn end
   is settled to :aborted."
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
  "History's user/assistant text as a <conversation_history> block for
   system-prompt injection (after /tree navigation the provider session is
   fresh), or nil."
  [history]
  (let [msgs (keep (fn [{:keys [kind text no-llm?]}]
                     (when (and (seq text) (not no-llm?))
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

(defn history->messages
  "A room's :history as a provider-agnostic message list for sessionless
   providers (Zen/Ollama replay the whole conversation): {:role :user :text},
   {:role :assistant :text :tool-calls}, {:role :tool :results}. Adjacent
   assistant output merges into one entry (strict alternation); thinking, error
   and aborted entries drop; tool calls without a result are skipped."
  [history]
  (letfn [(assistant-entry [acc]
            ;; Ensure the trailing acc entry is an open assistant message we can
            ;; extend; open a fresh one if the last entry is a different role.
            (if (= :assistant (:role (peek acc)))
              acc
              (conj acc {:role :assistant :text nil})))
          (tool-entry [acc]
            (if (= :tool (:role (peek acc)))
              acc
              (conj acc {:role :tool :results []})))]
    (reduce
     (fn [acc {:keys [kind text id tool arguments result is-error status]}]
       (case kind
         :user (if (seq text)
                 (conj acc {:role :user :text text})
                 acc)
         :text (if (seq text)
                 (let [acc (assistant-entry acc)]
                   (update acc (dec (count acc)) update :text
                           (fn [t] (if (seq t) (str t text) text))))
                 acc)
         :tool-call
         ;; Only replay completed calls (a result arrived). Skip running/aborted.
         (if (and id (contains? #{:done :error} status))
           (let [tc  {:id id :name tool :arguments (or arguments {})}
                 res {:id id :content (or result "") :is-error (boolean is-error)}]
             (if (= :tool (:role (peek acc)))
               ;; Mid tool-group: this call ran in the same assistant turn as
               ;; the open tool entry at the tail, so extend that assistant
               ;; message and its tool entry rather than opening new ones.
               (let [ti (dec (count acc))
                     ai (dec ti)]
                 (-> acc
                     (update ai update :tool-calls (fnil conj []) tc)
                     (update ti update :results (fnil conj []) res)))
               ;; Open (or reuse a text) assistant entry, then a tool entry.
               (let [acc (assistant-entry acc)
                     acc (update acc (dec (count acc)) update :tool-calls
                                 (fnil conj []) tc)
                     acc (tool-entry acc)]
                 (update acc (dec (count acc)) update :results
                         (fnil conj []) res))))
           acc)
         acc))
     []
     ;; Display-only entries carried over from before a /truncate are shown
     ;; in every client but never replayed to the model.
     (remove :no-llm? history))))

(defn- prompt-with-attachment-paths
  "Append the on-disk paths of attached files to the provider prompt (not the
   history entry) so file tools and sub-agents can reach them."
  [prompt images]
  (let [refs (keep (fn [{:keys [path media-type]}]
                     (when path
                       (if (str/starts-with? (or media-type "") "image/")
                         (str "[Attached image: " path "]")
                         (str "[Attached file: " path "]"))))
                   images)]
    (if (seq refs)
      (str/join "\n" (concat (some-> prompt str/trim not-empty vector) refs))
      prompt)))

(defn- build-turn-effect
  "The :provider/start-turn effect payload. Without :resume-id,
   :context-history is rendered into the system prompt; :prior-history lets
   sessionless providers rebuild the message array (Claude resumes server-side
   instead)."
  [{:keys [room resume-id prompt images context-history prior-history]}]
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
              :prompt   (prompt-with-attachment-paths prompt images)
              :model    (:model agent)
              :provider (:provider agent)
              :cwd      (:cwd room)
              :effort   (:effort agent)
              :system   system}
       (:only-tools agent) (assoc :only-tools (:only-tools agent))
       (seq images)   (assoc :images images)
       (seq prior-history) (assoc :history (vec prior-history))
       resume-id      (assoc :resume-session-id resume-id))]))

(defn- start-turn-effect
  "build-turn-effect from room state + prompt; a session flagged
   :inject-history? with no provider session to resume gets the history as
   context."
  [room {:keys [text images]}]
  (let [resume-id (get-in room [:session :provider-session-id])]
    (build-turn-effect
     {:room room
      :resume-id resume-id
      :prompt text
      :images images
      ;; `room` is the pre-append room: its history is the prior conversation,
      ;; which sessionless providers replay as earlier turns.
      :prior-history (:history room)
      :context-history (when (and (nil? resume-id)
                                  (get-in room [:session :inject-history?]))
                         (:history room))})))

(defn- begin-turn [st room prompt]
  ;; A live turn diverges the history from the resumed snapshot, so the
  ;; cached :msg-hash/:msg-count are cleared (else the web cache's prefix
  ;; check would double-append the tail).
  (let [label (:collapsed-label prompt)
        ;; The provider text carries a hidden marker for :collapsed-label, which
        ;; never reaches the transcript otherwise; messages->history rebuilds it.
        provider-prompt (cond-> prompt
                          label (update :text util/with-collapse-marker label))]
    {:state   (-> st
                  (update-in [:rooms (:id room) :history] conj
                             (cond-> {:kind :user :text (:text prompt) :images (:images prompt)
                                      ;; who sent it — clients label prompts from
                                      ;; other users by this id
                                      :user (:user prompt)}
                               label (assoc :collapsed-label label)))
                  (update-in [:rooms (:id room)] dissoc :msg-hash :msg-count)
                  (assoc-in [:rooms (:id room) :agent :busy?] true))
     :effects [(start-turn-effect room provider-prompt)]}))

(defn- join-prompts
  "Combine queued prompts into a single prompt: non-blank texts joined by
   blank lines, images concatenated in order."
  [prompts]
  (cond-> {:text   (->> (map :text prompts)
                        (remove str/blank?)
                        (str/join "\n\n"))
           :images (into [] (mapcat :images) prompts)
           ;; merged prompts from several users are attributed to the first
           :user   (:user (first prompts))}
    ;; A lone queued skill/command prompt keeps its collapsed label; once merged
    ;; with other queued prompts the single label no longer fits, so drop it.
    (and (= 1 (count prompts)) (:collapsed-label (first prompts)))
    (assoc :collapsed-label (:collapsed-label (first prompts)))))

;; ── Event handlers (pure) ────────────────────────────────────────────────────

(defn- prompt-submit [st {:keys [room-id text images collapsed-label] :as ev}]
  (when-let [room (state/get-room st room-id)]
    (let [prompt (cond-> {:text text :images images
                          ;; the server stamps :user on client events; local
                          ;; and server-side submits are this process' user
                          :user (state/event-user st ev)}
                   collapsed-label (assoc :collapsed-label collapsed-label))]
      (if (get-in room [:agent :busy?])
        ;; Busy — queue for after the current turn settles
        {:state (update-in st [:rooms room-id :agent :queued] (fnil conj []) prompt)}
        (begin-turn st room prompt)))))

(defn- text-delta [st {:keys [room-id text]}]
  (when (state/get-room st room-id)
    {:state (update-in st [:rooms room-id :history] fold-delta :text text)}))

(defn- thinking-delta [st {:keys [room-id text]}]
  ;; Some models stream thinking deltas as empty strings (reasoning
  ;; withheld); ignore them.
  (when (and (state/get-room st room-id) (seq text))
    {:state (update-in st [:rooms room-id :history] fold-delta :thinking text)}))

(defn- tool-start [st {:keys [room-id id tool arguments at]}]
  (when (state/get-room st room-id)
    {:state (update-in st [:rooms room-id :history] conj
                       (cond-> {:kind :tool-call :id id :tool tool
                                :arguments arguments :status :running}
                         at (assoc :started-at at)))}))

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
          ;; Asks still open for this turn's tool calls are moot once the turn is
          ;; over: drop them and settle their promises as denied.
          stale  (filterv :call (get-in room [:ui :dialogs]))
          st' (-> st
                  (update-in [:rooms room-id :history] finalize-history)
                  (update-in [:rooms room-id :agent]
                             #(-> %
                                  (assoc :busy? false :queued [])
                                  (cond->
                                   usage (assoc :last-usage usage)
                                   cost  (assoc :last-cost cost))))
                  (cond->
                   (seq stale)
                    (update-in [:rooms room-id :ui :dialogs]
                               (fn [ds] (vec (remove :call ds))))
                   provider-session-id
                    (assoc-in [:rooms room-id :session :provider-session-id]
                              provider-session-id)
                   aborted?
                    (update-in [:rooms room-id :history] conj {:kind :aborted})))
          effects (cond-> (mapv (fn [{:keys [id]}]
                                  [:dialog/resolve {:dialog-id id :value false}])
                                stale)
                    ;; Drain the whole queue as one combined prompt by
                    ;; re-dispatching — keeps a single submission code path.
                    (seq queued)
                    (conj [:app/dispatch (merge {:type :prompt/submit :room-id room-id}
                                                (join-prompts queued))]))]
      (cond-> {:state st'}
        (seq effects) (assoc :effects effects)))))

(defn- retry-fresh
  "The provider could not start (dead resume id, missing cwd since fixed): drop
   the id and re-run the pending turn fresh with the in-memory history as
   context. The pending prompt is the last :user entry (a later status line may
   follow it)."
  [st {:keys [room-id]}]
  (when-let [room (state/get-room st room-id)]
    (let [history (:history room)
          idx     (->> (map-indexed vector history)
                       (filter #(= :user (:kind (second %))))
                       (map first)
                       last)]
      (when idx
        (let [user-ent (nth history idx)
              ;; Drop the dead id but keep it on record as superseded, so a
              ;; transcript it did leave behind stays claimed by this session.
              room'    (update room :session state/drop-provider-session)]
          {:state   (assoc-in st [:rooms room-id :session] (:session room'))
           :effects [(build-turn-effect
                      {:room room'
                       :resume-id nil
                       :prompt (:text user-ent)
                       :images (:images user-ent)
                       :prior-history (subvec (vec history) 0 idx)
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
      (get providers (util/provider-for-model model))
      (get providers :anthropic)
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
  [dispatch! room-id]
  {:on-text        (fn [text]
                     (dispatch! {:type :agent/text-delta :room-id room-id :text text}))
   :on-thinking    (fn [text]
                     (dispatch! {:type :agent/thinking-delta :room-id room-id :text text}))
   :on-tool-start  (fn [{:keys [id name arguments]}]
                     (dispatch! {:type :agent/tool-start :room-id room-id
                                 :id id :tool name :arguments arguments
                                 :at (js/Date.now)}))
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


(defn room-client-pid
  "Pid of the client driving `room-id` (TUI preferred), from the connection
   registry; this process' pid in standalone. MCP servers get it as _meta
   (xi.ext.mcp/call-meta)."
  [st room-id]
  (or (->> (vals (get-in st [:connection :clients]))
           (filter (fn [c] (and (= room-id (:room-id c)) (:pid c))))
           (sort-by (fn [c] (if (= "tui" (:platform c)) 0 1)))
           first
           :pid)
      (when (= :standalone (get-in st [:connection :mode]))
        (.-pid js/process))))

(defn create-fx
  "Provider effects over `providers` (id → provider); in-flight turn handles
   live here. The second arity threads extension tooling into every turn:
   :tool-policy (the rules engine, wrapped with the per-turn ctx),
   :extra-tool-definitions, :extra-tool-registry, :remove-tools, :ask! (as the
   tool ctx's :confirm!), :turn-finished! (holds settle) and :fx. All from
   node-only xi.cli; this ns is shared with the browser build."
  ([providers] (create-fx providers nil))
  ([providers {:keys [tool-policy extra-tool-definitions extra-tool-registry remove-tools ask!
                      turn-finished! fx]}]
  (let [inflight (js/Map.)]
   (merge
    fx
    {:provider/start-turn
     (fn [{:keys [dispatch! get-state]} {:keys [room-id cwd] :as payload}]
       (let [provider (resolve-provider providers payload)
             ;; Per-turn context for the tool policy and tool exec-fns (as :tool-ctx):
             ;; live state, dispatch, and confirm dialogs.
             tool-ctx {:dispatch! dispatch!
                       :get-state get-state
                       :room-id   room-id
                       :cwd       cwd
                       ;; who the turn acts for: the sender of the latest
                       ;; prompt. xi.api.user reads it as "the current user".
                       :user      (state/turn-user (get-state) room-id)
                       ;; Raise a confirm dialog and resolve to the answer; it waits in the room
                       ;; even with no client connected (prompt mode resolves the safe default,
                       ;; xi.ext.core/create-dialogs). opts: :options, :diff, :call, :target,
                       ;; :block, :on-reason (see xi.dialog).
                       :confirm!  (when ask!
                                    (fn confirm!
                                      ([message] (confirm! message nil))
                                      ([message opts]
                                       (ask! {:dispatch! dispatch! :state (get-state)}
                                             {:room-id   room-id
                                              :on-reason (:on-reason opts)
                                              :dialog  (cond-> {:type :confirm :message message}
                                                         (:options opts) (assoc :options (:options opts))
                                                         (:diff opts)    (assoc :diff (:diff opts))
                                                         (:call opts)    (assoc :call (:call opts))
                                                         (:target opts)  (assoc :target (:target opts))
                                                         (:block opts)   (assoc :block (:block opts)))}))))}
             policy1 (when tool-policy
                     (fn [tool-call]
                       (tool-policy tool-call (dialog/scope-confirm-to-call tool-ctx tool-call))))
             ;; The turn's cwd doesn't exist on this host: ask where to run, persist
             ;; it, replay the turn.
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
              (cond-> (merge payload (event-callbacks dispatch! room-id)
                             {:tool-ctx tool-ctx})
                policy1                  (assoc :tool-policy policy1)
                extra-tool-definitions (assoc :extra-tool-definitions extra-tool-definitions)
                extra-tool-registry    (assoc :extra-tool-registry extra-tool-registry)
                remove-tools           (assoc :remove-tools remove-tools)
                client-pid             (assoc :client-pid client-pid)))]
         ;; `handle` identifies this turn in `inflight`; a discarded turn
         ;; (:provider/discard) loses its entry so its late resolution is dropped.
         (let [handle {:abort! abort!}
               current? (fn [] (identical? handle (.get inflight room-id)))]
           (.set inflight room-id handle)
           (-> promise
               (.then
                (fn [result]
                  (when (current?)
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
                                  :aborted? (boolean (:aborted result))})))))
               (.catch
                (fn [err]
                  (when (current?)
                    (.delete inflight room-id)
                    (dispatch! {:type :agent/error :room-id room-id
                                :error {:type "error" :message (str (.-message err))}})
                    (dispatch! {:type :agent/turn-end :room-id room-id}))))
               ;; Every turn reports back (xi.holds/settle-room! via xi.cli).
               (.finally #(when turn-finished! (turn-finished! room-id cwd)))))))

     :provider/abort
     (fn [_ {:keys [room-id]}]
       (when-let [handle (.get inflight room-id)]
         ((:abort! handle))))

     ;; Abort the in-flight turn and drop its entry so its late resolution
     ;; can't dispatch turn-end into the replaced session (/new, /clear).
     :provider/discard
     (fn [_ {:keys [room-id]}]
       (when-let [handle (.get inflight room-id)]
         (.delete inflight room-id)
         ((:abort! handle))))}))))
