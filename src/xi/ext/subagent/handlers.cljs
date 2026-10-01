(ns xi.ext.subagent.handlers
  "Pure, browser-safe state handlers for the sub-agents extension.

   Shared by both halves: the node ext (xi.ext.subagent — tools, gate, the
   turn-running :subagent/start effect) and the web ext (xi.ext.subagent.web —
   registers just these handlers so the mirrored :subagent/* broadcasts build
   the Sub-agents panel state client-side).

   State is room-scoped, so it rides in :room/joined snapshots and mirrors to
   every client:
     [:rooms rid :ext :subagents]
       {:agents [{:id :label :task :status :history [] :result
                  :started :ended :errored? :expanded?}]
        :collapsed? bool}
   status: :running | :done | :error | :stopped"
  (:require [clojure.string :as str]
            [xi.agent :as agent]
            [xi.core.state :as state]))

(def ext-id :subagents)

;; ── Helpers ──────────────────────────────────────────────────────────────────

(defn agents [st room-id]
  (or (:agents (state/room-ext st room-id ext-id)) []))

(defn find-child [st room-id sub-id]
  (some #(when (= sub-id (:id %)) %) (agents st room-id)))

(defn- update-child
  "Apply f to the child with matching sub-id in room state."
  [st room-id sub-id f]
  (update-in st [:rooms room-id :ext ext-id :agents]
             (fn [as] (mapv (fn [a] (if (= sub-id (:id a)) (f a) a)) as))))

(defn gen-id [prefix]
  (str prefix "-" (.toString (js/Math.floor (* (js/Math.random) 1e9)) 36)))

(defn default-label [task]
  (let [t (str/trim (str task))
        one-line (first (str/split-lines t))]
    (if (> (count one-line) 48) (str (subs one-line 0 48) "…") one-line)))

(defn final-text
  "The sub-agent's last assistant text entry — the concise result the parent
   polls for."
  [history]
  (->> history (filter #(= :text (:kind %))) last :text))

(def ^:private SUBAGENT_PREAMBLE
  (str "You are an autonomous SUB-AGENT spawned to handle one focused task in "
       "your own separate context. Work independently, use your tools as "
       "needed, and finish with a single concise message that captures the "
       "result — that final message is what the parent agent reads back. Do "
       "not ask the parent questions; make reasonable assumptions and proceed."))

(defn child-system
  "System prompt for a sub-agent turn: the room's base system (AGENTS.md etc.)
   + the sub-agent preamble + any task-specific system text."
  [room extra]
  (->> [(get-in room [:agent :system]) SUBAGENT_PREAMBLE extra]
       (remove str/blank?)
       (str/join "\n\n")))

;; ── State handlers (pure) ─────────────────────────────────────────────────────

(defn- spawn
  "Append a sub-agent entry and kick off its turn. The provider settings come
   from the room's agent; the child runs a FRESH context (no resume).

   Emits the :subagent/start effect — the node build runs the turn; the web
   build registers a no-op for it (the turn only ever runs server-side)."
  [st {:keys [room-id sub-id task label prompt system]}]
  (when-let [room (state/get-room st room-id)]
    (let [sub-id (or sub-id (gen-id "sa"))
          child  {:id      sub-id
                  :label   (or label (default-label task))
                  :task    task
                  :status  :running
                  :history []
                  :started (.now js/Date)}]
      {:state   (update-in st [:rooms room-id :ext ext-id :agents]
                           (fnil conj []) child)
       :effects [[:subagent/start
                  {:room-id         room-id
                   :sub-id          sub-id
                   :prompt          (or prompt task)
                   :system          (child-system room system)
                   :model           (get-in room [:agent :model])
                   :provider        (get-in room [:agent :provider])
                   :effort          (get-in room [:agent :effort])
                   :cwd             (:cwd room)
                   ;; An agent profile's tool allowlist binds sub-agents too.
                   :only-tools      (get-in room [:agent :only-tools])}]]})))

(defn- text-delta [st {:keys [room-id sub-id text]}]
  (when (find-child st room-id sub-id)
    {:state (update-child st room-id sub-id
                          #(update % :history agent/fold-delta :text text))}))

(defn- thinking-delta [st {:keys [room-id sub-id text]}]
  ;; Blank deltas → no entry; see xi.agent/thinking-delta.
  (when (and (find-child st room-id sub-id) (seq text))
    {:state (update-child st room-id sub-id
                          #(update % :history agent/fold-delta :thinking text))}))

(defn- tool-start [st {:keys [room-id sub-id id tool arguments]}]
  (when (find-child st room-id sub-id)
    {:state (update-child st room-id sub-id
                          #(update % :history conj
                                   {:kind :tool-call :id id :tool tool
                                    :arguments arguments :status :running}))}))

(defn- tool-args [st {:keys [room-id sub-id id arguments]}]
  (when (find-child st room-id sub-id)
    {:state (update-child st room-id sub-id
                          #(update % :history agent/update-tool-call id
                                   (fn [tc] (assoc tc :arguments arguments))))}))

(defn- tool-result [st {:keys [room-id sub-id id content is-error]}]
  (when (find-child st room-id sub-id)
    {:state (update-child st room-id sub-id
                          #(update % :history agent/update-tool-call id
                                   (fn [tc] (assoc tc :result content
                                                   :is-error (boolean is-error)
                                                   :status (if is-error :error :done)))))}))

(defn- sub-error [st {:keys [room-id sub-id error]}]
  (when (find-child st room-id sub-id)
    {:state (update-child st room-id sub-id
                          #(-> % (assoc :errored? true)
                               (update :history conj {:kind :error :error error})))}))

(defn- turn-end [st {:keys [room-id sub-id aborted?]}]
  (when (find-child st room-id sub-id)
    {:state (update-child st room-id sub-id
                          (fn [a]
                            (let [history (agent/finalize-history (:history a))]
                              (assoc a
                                     :history history
                                     :ended  (.now js/Date)
                                     :result (final-text history)
                                     :status (cond aborted?        :stopped
                                                   (:errored? a)   :error
                                                   :else           :done)))))}))

(defn- session-init
  "The provider reported the child's CLI session id — remember it so the
   sub-agent can later be promoted to a full session."
  [st {:keys [room-id sub-id cli-session-id]}]
  (when (find-child st room-id sub-id)
    {:state (update-child st room-id sub-id #(assoc % :cli-session-id cli-session-id))}))

(defn promoted
  "A sub-agent got promoted to a full session: record the session id on the
   child and link it into the parent session's :promoted-subagents."
  [st {:keys [room-id sub-id session-id label]}]
  (when (find-child st room-id sub-id)
    {:state (-> st
                (update-child room-id sub-id #(assoc % :session-id session-id))
                (update-in [:rooms room-id :session :promoted-subagents]
                           (fn [ps]
                             (if (some #(= sub-id (:sub-id %)) ps)
                               ps
                               (conj (vec ps) {:sub-id sub-id :label label
                                               :session-id session-id})))))}))

(defn on-session-resumed
  "Rehydrate promoted sub-agent stubs from the resumed session's
   :promoted-subagents so the panel links survive restarts.

   NOT in the shared `handlers` map: on the web, extension handlers install
   as :local-handlers, which plain-merge OVER the wrapped base map — a
   :session/resumed entry here would shadow the core resume handler and
   break resume. The node extension registers it instead (where
   ext/merge-handlers chains it after core); web clients receive the
   rehydrated ext state via the :room/joined snapshot."
  [st {:keys [room-id session]}]
  (when-let [promoted-subs (seq (:promoted-subagents session))]
    (let [existing (set (map :id (agents st room-id)))
          stubs    (->> promoted-subs
                        (remove #(existing (:sub-id %)))
                        (map (fn [{:keys [sub-id label session-id]}]
                               {:id sub-id :label label :status :done
                                :history [] :session-id session-id})))]
      (when (seq stubs)
        {:state (update-in st [:rooms room-id :ext ext-id :agents] (fnil into []) stubs)}))))

(defn- toggle-collapse [st {:keys [room-id]}]
  (when (state/get-room st room-id)
    {:state (update-in st [:rooms room-id :ext ext-id :collapsed?] not)}))

(defn- toggle-child
  "Expand/collapse one child's history in the web panel. Children default
   collapsed (:expanded? absent) so N running agents render only their heads;
   this opt-in flag streams a single child's full history on demand."
  [st {:keys [room-id sub-id]}]
  (when (find-child st room-id sub-id)
    {:state (update-child st room-id sub-id #(update % :expanded? not))}))

(def handlers
  "The pure state-updating handlers, shared by the node + web builds."
  {:subagent/spawn           spawn
   :subagent/text-delta      text-delta
   :subagent/thinking-delta  thinking-delta
   :subagent/tool-start      tool-start
   :subagent/tool-args       tool-args
   :subagent/tool-result     tool-result
   :subagent/error           sub-error
   :subagent/turn-end        turn-end
   :subagent/session-init    session-init
   :subagent/promoted        promoted
   :subagent/toggle-collapse toggle-collapse
   :subagent/toggle-child    toggle-child})
