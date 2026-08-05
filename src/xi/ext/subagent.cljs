(ns xi.ext.subagent
  "Sub-agents as background processes.

   The agent (or a task command like /review) spawns a sub-agent: a background
   agent turn that runs in its OWN fresh provider context (see xi.subagent), so
   its verbose work never enters the parent conversation. The parent LLM starts
   one and polls it — spawn_subagent → list_subagents / subagent_result →
   stop_subagent — mirroring the process-manager tool shape.

   The LLM cannot run a sub-agent without user approval: spawn_subagent goes
   through a :confirm! dialog first (blocked when no client is attached).

   State is room-scoped, so it rides in :room/joined snapshots and mirrors to
   every client (the web Sub-agents panel builds up live):
     [:rooms rid :ext :subagents]
       {:agents [{:id :label :task :status :history [] :result
                  :started :ended :errored? :collapsed?}]
        :collapsed? bool}
   status: :running | :done | :error | :stopped

   The turn itself is run by the :subagent/start effect (xi.subagent, wired in
   xi.cli next to agent/create-fx). This namespace is the pure data surface:
   tools, gate, state handlers, system prompt."
  (:require [clojure.string :as str]
            [xi.agent :as agent]
            [xi.core.state :as state]
            [xi.subagent :as subagent]))

(def ^:private ext-id :subagents)

;; ── Helpers ──────────────────────────────────────────────────────────────────

(defn- agents [st room-id]
  (or (:agents (state/room-ext st room-id ext-id)) []))

(defn- find-child [st room-id sub-id]
  (some #(when (= sub-id (:id %)) %) (agents st room-id)))

(defn- update-child
  "Apply f to the child with matching sub-id in room state."
  [st room-id sub-id f]
  (update-in st [:rooms room-id :ext ext-id :agents]
             (fn [as] (mapv (fn [a] (if (= sub-id (:id a)) (f a) a)) as))))

(defn- gen-id [prefix]
  (str prefix "-" (.toString (js/Math.floor (* (js/Math.random) 1e9)) 36)))

(defn- default-label [task]
  (let [t (str/trim (str task))
        one-line (first (str/split-lines t))]
    (if (> (count one-line) 48) (str (subs one-line 0 48) "…") one-line)))

(defn- final-text
  "The sub-agent's last assistant text entry — the concise result the parent
   polls for."
  [history]
  (->> history (filter #(= :text (:kind %))) last :text))

(defn- text-result [s]
  {:content [{:type "text" :text s}] :is-error false})

(defn- format-duration [ms]
  (let [secs (quot ms 1000) mins (quot secs 60)]
    (cond (>= mins 1) (str mins "m " (mod secs 60) "s")
          :else       (str secs "s"))))

(def ^:private SUBAGENT_PREAMBLE
  (str "You are an autonomous SUB-AGENT spawned to handle one focused task in "
       "your own separate context. Work independently, use your tools as "
       "needed, and finish with a single concise message that captures the "
       "result — that final message is what the parent agent reads back. Do "
       "not ask the parent questions; make reasonable assumptions and proceed."))

(defn- child-system
  "System prompt for a sub-agent turn: the room's base system (AGENTS.md etc.)
   + the sub-agent preamble + any task-specific system text."
  [room extra]
  (->> [(get-in room [:agent :system]) SUBAGENT_PREAMBLE extra]
       (remove str/blank?)
       (str/join "\n\n")))

;; ── State handlers (pure) ─────────────────────────────────────────────────────

(defn- spawn
  "Append a sub-agent entry and kick off its turn. The provider settings come
   from the room's agent; the child runs a FRESH context (no resume)."
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
                   :personal-agent? (get-in room [:agent :personal-agent?])}]]})))

(defn- text-delta [st {:keys [room-id sub-id text]}]
  (when (find-child st room-id sub-id)
    {:state (update-child st room-id sub-id
                          #(update % :history agent/fold-delta :text text))}))

(defn- thinking-delta [st {:keys [room-id sub-id text]}]
  (when (find-child st room-id sub-id)
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
  (when-let [child (find-child st room-id sub-id)]
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

(defn- toggle-collapse [st {:keys [room-id]}]
  (when (state/get-room st room-id)
    {:state (update-in st [:rooms room-id :ext ext-id :collapsed?] not)}))

(defn- toggle-child [st {:keys [room-id sub-id]}]
  (when (find-child st room-id sub-id)
    {:state (update-child st room-id sub-id #(update % :collapsed? not))}))

(defn- on-room-close
  "Abort any running sub-agents when a room is destroyed."
  [st {:keys [room-id]}]
  (doseq [{:keys [id]} (agents st room-id)]
    (subagent/abort! id))
  nil)

;; ── Tools (handled in the gate — they need dispatch!/get-state/room-id) ───────

(def ^:private tool-defs
  [{:name "spawn_subagent"
    :description (str "Spawn a background sub-agent to handle a focused, "
                      "self-contained task (research, a code review, building a "
                      "review canvas, a scoped refactor) in its OWN separate "
                      "context, so it does not clutter this conversation. It "
                      "runs in the background: this call returns immediately "
                      "with an id. Poll progress with `list_subagents` and read "
                      "its output with `subagent_result` once it is done. The "
                      "user must approve every spawn.")
    :input_schema {:type "object"
                   :properties {:task  {:type "string"
                                        :description "The full task/instructions for the sub-agent (it starts fresh with no other context, so be self-contained)."}
                                :label {:type "string"
                                        :description "Short label for the sub-agent in the UI (optional)."}}
                   :required ["task"]}}
   {:name "list_subagents"
    :description "List this room's sub-agents with their status, uptime, and a snippet of recent output."
    :input_schema {:type "object" :properties {} :required []}}
   {:name "subagent_result"
    :description (str "Fetch a sub-agent's result by its id. Returns the final "
                      "output once it is done, or a 'still running' notice with "
                      "recent output otherwise (call again later to poll).")
    :input_schema {:type "object"
                   :properties {:id {:type "string" :description "The sub-agent id from spawn_subagent / list_subagents."}}
                   :required ["id"]}}
   {:name "stop_subagent"
    :description "Stop a running sub-agent by its id."
    :input_schema {:type "object"
                   :properties {:id {:type "string" :description "The sub-agent id to stop."}}
                   :required ["id"]}}])

(def ^:private tool-names (set (map :name tool-defs)))

(defn- recent-snippet [child]
  (let [t (or (final-text (:history child))
              (some->> (:history child) (filter #(= :thinking (:kind %))) last :text))]
    (some-> t str/trim str/split-lines last)))

(defn- format-list [as]
  (if (empty? as)
    "No sub-agents in this room."
    (let [now (.now js/Date)]
      (str "Sub-agents:\n"
           (str/join
            "\n"
            (map (fn [{:keys [id label status started ended] :as child}]
                   (let [dur (format-duration (- (or ended now) started))
                         snip (recent-snippet child)]
                     (str "  • [" (name status) "] " id
                          (when label (str " — " label))
                          " (" dur ")"
                          (when (and (= status :running) snip)
                            (str "\n      … " snip)))))
                 as))))))

(defn- spawn-result [sub-id label]
  (text-result
   (str "Sub-agent started" (when label (str " '" label "'"))
        " (id " sub-id "). It runs in the background in its own context — this "
        "does NOT block you. Poll with `list_subagents`, and read its output "
        "with `subagent_result` (id \"" sub-id "\") once it reports done. Stop "
        "it early with `stop_subagent`.")))

(def ^:private blocked-result
  {:intercepted true
   :result {:content [{:type "text"
                       :text "Sub-agent not started — the user declined (or no client is attached to approve it)."}]
            :is-error true}})

(defn- spawn-tool
  "Confirm with the user, then spawn. Returns a promise of an intercepted
   result (id on approval, blocked otherwise)."
  [tool-call {:keys [dispatch! room-id confirm!]}]
  (let [{:keys [task label]} (:arguments tool-call)
        sub-id (gen-id "sa")
        label  (or (not-empty label) (default-label task))]
    (if confirm!
      (-> (confirm! (str "Spawn sub-agent '" label "'?"))
          (.then (fn [ok?]
                   (if ok?
                     (do (dispatch! {:type :subagent/spawn :room-id room-id
                                     :sub-id sub-id :task task :label label :prompt task})
                         {:intercepted true :result (spawn-result sub-id label)})
                     blocked-result))))
      (js/Promise.resolve blocked-result))))

(defn- result-tool [id st room-id]
  (let [child (find-child st room-id id)]
    (cond
      (nil? child)
      {:intercepted true :result (text-result (str "No sub-agent with id " id "."))}

      (= :running (:status child))
      {:intercepted true
       :result (text-result
                (str "Sub-agent " id " is still running. Recent output:\n"
                     (or (recent-snippet child) "(no output yet)")
                     "\nCall subagent_result again later to poll."))}

      :else
      {:intercepted true
       :result (text-result
                (str "Sub-agent " id " [" (name (:status child)) "]:\n\n"
                     (or (:result child) "(no textual output)")))})))

(defn- stop-tool [id room-id dispatch!]
  (if (subagent/running? id)
    (do (dispatch! {:type :subagent/abort :sub-id id})
        {:intercepted true :result (text-result (str "Stopping sub-agent " id "."))})
    {:intercepted true :result (text-result (str "Sub-agent " id " is not running."))}))

(defn- tool-gate
  [tool-call {:keys [dispatch! get-state room-id] :as ctx}]
  (let [{:keys [name arguments]} tool-call]
    (if-not (tool-names name)
      tool-call
      (case name
        "spawn_subagent"  (spawn-tool tool-call ctx)
        "list_subagents"  {:intercepted true
                           :result (text-result (format-list (agents (get-state) room-id)))}
        "subagent_result" (result-tool (:id arguments) (get-state) room-id)
        "stop_subagent"   (stop-tool (:id arguments) room-id dispatch!)
        tool-call))))

;; ── Command (TUI visibility) ─────────────────────────────────────────────────

(defn- cmd-subagents [st {:keys [room-id]}]
  {:state (update-in st [:rooms room-id :history] conj
                     {:kind :status :text (format-list (agents st room-id))})})

;; ── System prompt ─────────────────────────────────────────────────────────────

(def ^:private system-prompt
  (str "## Sub-agents\n"
       "For a focused, self-contained task that would otherwise clutter this "
       "conversation with a lot of intermediate work — a code review, building "
       "a review canvas, deep research, a scoped refactor — you can spawn a "
       "background SUB-AGENT with `spawn_subagent`. It runs in its own separate "
       "context (nothing it does enters this conversation) and returns "
       "immediately with an id; it does NOT block you. The user must approve "
       "each spawn. Poll it with `list_subagents` and read its final output "
       "with `subagent_result` once it is done; `stop_subagent` cancels one. "
       "Make each task self-contained — the sub-agent starts fresh with no "
       "other context."))

;; ── Extension ─────────────────────────────────────────────────────────────────

(def extension
  {:id               ext-id
   :init             {:room {:agents [] :collapsed? false}}
   :system-prompt    system-prompt
   :tool-definitions tool-defs
   :tool-gate        tool-gate
   :handlers         {:subagent/spawn           spawn
                      :subagent/text-delta      text-delta
                      :subagent/thinking-delta  thinking-delta
                      :subagent/tool-start      tool-start
                      :subagent/tool-args       tool-args
                      :subagent/tool-result     tool-result
                      :subagent/error           sub-error
                      :subagent/turn-end        turn-end
                      :subagent/toggle-collapse toggle-collapse
                      :subagent/toggle-child    toggle-child
                      :room/close               on-room-close}
   :commands         [{:name "subagents"
                       :description "List this room's background sub-agents"
                       :handler cmd-subagents}]})