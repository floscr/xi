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
                  :started :ended :errored? :expanded?}]
        :collapsed? bool}
   status: :running | :done | :error | :stopped

   The turn itself is run by the :subagent/start effect (xi.subagent, wired in
   xi.cli next to agent/create-fx). This namespace is the node data surface:
   tools, gate, the turn-running effect wiring, system prompt. The pure state
   handlers live in xi.ext.subagent.handlers so the web half can reuse them."
  (:require [clojure.string :as str]
            [xi.ext.subagent.handlers :as h]
            [xi.subagent :as subagent]))

(def ^:private ext-id h/ext-id)

;; ── Helpers ──────────────────────────────────────────────────────────────────

(def ^:private agents h/agents)
(def ^:private find-child h/find-child)
(def ^:private final-text h/final-text)
(def ^:private gen-id h/gen-id)
(def ^:private default-label h/default-label)

(defn- text-result [s]
  {:content [{:type "text" :text s}] :is-error false})

(defn- format-duration [ms]
  (let [secs (quot ms 1000) mins (quot secs 60)]
    (cond (>= mins 1) (str mins "m " (mod secs 60) "s")
          :else       (str secs "s"))))

;; ── State handlers (pure) — see xi.ext.subagent.handlers ─────────────────────

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
   :handlers         (assoc h/handlers :room/close on-room-close)
   :commands         [{:name "subagents"
                       :description "List this room's background sub-agents"
                       :handler cmd-subagents}]})