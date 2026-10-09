(ns xi.ext.subagent
  "Sub-agents as background processes.

   The agent (or a user-initiated command) spawns a sub-agent: a background
   agent turn that runs in its OWN fresh provider context (see xi.subagent), so
   its verbose work never enters the parent conversation. The parent LLM starts
   one and polls it — spawn_subagent → list_subagents / subagent_result →
   stop_subagent — mirroring the process-manager tool shape.

   The LLM cannot run a sub-agent without user approval: the
   `::subagent-confirm` default rule (xi.rules.defaults) asks before every
   spawn_subagent call (denied when no client is attached).

   State is room-scoped, so it rides in :room/joined snapshots and mirrors to
   every client (the web Sub-agents panel builds up live):
     [:rooms rid :ext :subagents]
       {:agents [{:id :label :task :status :history [] :result
                  :started :ended :errored? :expanded?}]
        :collapsed? bool}
   status: :running | :done | :error | :stopped

   The turn itself is run by the :subagent/start effect (xi.subagent, wired in
   xi.cli next to agent/create-fx). This namespace is the node data surface:
   tools, the turn-running effect wiring, system prompt. The pure state
   handlers live in xi.ext.subagent.handlers so the web half can reuse them."
  (:require [clojure.string :as str]
            [xi.core.state :as state]
            [xi.ext.subagent.handlers :as h]
            [xi.session :as session]
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
  "Abort any running sub-agents when a room is destroyed, and drop the
   throwaway config dirs kept around for promotion."
  [st {:keys [room-id]}]
  (doseq [{:keys [id]} (agents st room-id)]
    (subagent/abort! id)
    (subagent/cleanup-dir! id))
  nil)

(defn- abort-sub
  "User/tool-initiated stop of one sub-agent. Emits the :subagent/abort
   effect (xi.subagent) — the turn's own turn-end (:aborted? true) then
   flips the child's status to :stopped. Dispatched by stop_subagent, the
   TUI sub-agents buffer (x) and the web panel's stop button; carries
   :room-id so the server broadcasts it room-scoped."
  [st {:keys [room-id sub-id]}]
  (when (find-child st room-id sub-id)
    {:effects [[:subagent/abort {:sub-id sub-id}]]}))

(defn- room-for-session
  "The id of the live room on session `session-id`, or nil."
  [st session-id]
  (some (fn [[rid room]] (when (= session-id (get-in room [:session :id])) rid))
        (:rooms st)))

(defn- session-sub-handler
  "A roomless handler re-dispatching the room-scoped `event-type`
   (:subagent/abort or :subagent/dismiss) into the live room of the event's
   :session-id. The web sidebar lists a room's sub-agents with its buffers
   (xi.server.room-manager/session-buffers) and a row's button acts on one
   from anywhere — but the server pins a client's room events to the room
   it is in, so, like :session/buffer-close, the row names the session and
   the server finds the room (its clients mirror the re-dispatch). No live
   room: nothing to act on (sub-agents are room state)."
  [event-type]
  (fn [st {:keys [session-id sub-id]}]
    (when-let [rid (room-for-session st session-id)]
      {:effects [[:app/dispatch {:type event-type :room-id rid :sub-id sub-id}]]})))

(defn- promote-sub
  "Open a sub-agent as a full chat. First open promotes it — the
   :subagent/promote! effect moves its transcript into a real session —
   after which the child carries :session-id and opening is just a resume."
  [st {:keys [room-id sub-id open?]}]
  (when-let [child (find-child st room-id sub-id)]
    (cond
      (:session-id child)
      (when open?
        {:effects [[:session/load {:room-id room-id :scope :all
                                   :session-id (:session-id child)}]]})

      (= :running (:status child))
      {:effects [[:app/dispatch {:type :ui/status :room-id room-id
                                 :text "Sub-agent is still running — wait for it to finish before opening it as a chat."}]]}

      :else
      {:effects [[:subagent/promote! {:room-id room-id :sub-id sub-id
                                      :open? open?}]]})))

(defn- promote-fx
  "Promote a finished sub-agent to a real session: move its transcript out of
   the throwaway config dir, persist the parent → child link, broadcast
   :subagent/promoted, and (optionally) resume it into this room."
  [{:keys [dispatch! get-state]} {:keys [room-id sub-id open?]}]
  (let [st     (get-state)
        room   (get-in st [:rooms room-id])
        child  (find-child st room-id sub-id)
        parent (:session room)
        cfg    (subagent/config-dir sub-id)
        saved  (when (and child cfg (:cli-session-id child))
                 (session/promote-subagent-session!
                  {:config-dir     cfg
                   :cwd            (:cwd room)
                   :cli-session-id (:cli-session-id child)
                   :label          (:label child)
                   :origin         {:session-id (:id parent)
                                    :sub-id     sub-id}}))]
    (if-not saved
      (dispatch! {:type :ui/status :room-id room-id
                  :text "Couldn't open this sub-agent as a chat — no transcript found (non-Claude sub-agents can't be promoted)."})
      (do
        (subagent/cleanup-dir! sub-id)
        ;; Persist the link on the parent so it survives restarts.
        (when parent
          (session/update-session!
           parent
           {:promoted-subagents
            (let [ps (vec (:promoted-subagents parent))]
              (if (some #(= sub-id (:sub-id %)) ps)
                ps
                (conj ps {:sub-id sub-id :label (:label child)
                          :session-id (:id saved)})))}))
        (dispatch! {:type :subagent/promoted :room-id room-id :sub-id sub-id
                    :session-id (:id saved) :label (:label child)})
        (when open?
          ;; Resolve the freshly saved session into a listing summary
          ;; (find-session-by-id sees hidden sessions) and resume it here.
          (when-let [summary (session/find-session-by-id (:id saved))]
            (dispatch! {:type :session/resumed
                        :room-id room-id
                        :session (session/load-session summary)
                        :summary summary
                        :messages (session/read-session-messages summary)})))))))

(defn- explain-call
  "h/explain-call with the parent transcript's path (a node-only lookup), so
   the explanation sub-agent can read the full conversation when the excerpt
   in its prompt isn't enough."
  [st {:keys [room-id] :as ev}]
  (let [room (state/get-room st room-id)
        sess (:session room)]
    (h/explain-call st (assoc ev :transcript
                              (session/transcript-path (or (:cwd sess) (:cwd room))
                                                       (:provider-session-id sess))))))

;; ── Tools (registry fns; dispatch!/get-state/room-id come from the tool ctx) ──

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
                   (let [dur (when started
                               (format-duration (- (or ended now) started)))
                         snip (recent-snippet child)]
                     (str "  • [" (name status) "] " id
                          (when label (str " — " label))
                          (when dur (str " (" dur ")"))
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

(defn- spawn-tool
  "Spawn the sub-agent and return its id. Approval is policy — the
   `::subagent-confirm` default rule asks before this ever runs."
  [{:keys [task label]} {:keys [dispatch! room-id]}]
  (let [sub-id (gen-id "sa")
        label  (or (not-empty label) (default-label task))]
    (dispatch! {:type :subagent/spawn :room-id room-id
                :sub-id sub-id :task task :label label :prompt task})
    (spawn-result sub-id label)))

(defn- result-tool [{:keys [id]} {:keys [get-state room-id]}]
  (let [child (find-child (get-state) room-id id)]
    (cond
      (nil? child)
      (text-result (str "No sub-agent with id " id "."))

      (= :running (:status child))
      (text-result
       (str "Sub-agent " id " is still running. Recent output:\n"
            (or (recent-snippet child) "(no output yet)")
            "\nCall subagent_result again later to poll."))

      :else
      (text-result
       (str "Sub-agent " id " [" (name (:status child)) "]:\n\n"
            (or (:result child) "(no textual output)"))))))

(defn- stop-tool [{:keys [id]} {:keys [dispatch! room-id]}]
  (if (subagent/running? id)
    (do (dispatch! {:type :subagent/abort :room-id room-id :sub-id id})
        (text-result (str "Stopping sub-agent " id ".")))
    (text-result (str "Sub-agent " id " is not running."))))

(defn- list-tool [_args {:keys [get-state room-id]}]
  (text-result (format-list (agents (get-state) room-id))))

(defn- with-room-ctx
  "Guard a tool fn on the tool ctx carrying dispatch!/get-state (always true
   inside an agent turn)."
  [f]
  (fn [args {:keys [dispatch! get-state] :as ctx}]
    (if (and dispatch! get-state)
      (f args ctx)
      {:content [{:type "text" :text "sub-agents: no room context"}] :is-error true})))

(def ^:private tool-registry
  {"spawn_subagent"  (with-room-ctx spawn-tool)
   "list_subagents"  (with-room-ctx list-tool)
   "subagent_result" (with-room-ctx result-tool)
   "stop_subagent"   (with-room-ctx stop-tool)})

;; ── Command (TUI visibility) ─────────────────────────────────────────────────

(defn- cmd-subagents
  "Open the live :subagents buffer (TUI: navigable pager; the web shows its
   inline panel regardless, so the buffer id just falls back to chat there)."
  [st {:keys [room-id]}]
  {:state (assoc-in st [:rooms room-id :ui :active-buffer] :subagents)})

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
   :tool-registry    tool-registry
   :handlers         (assoc h/handlers
                            :room/close on-room-close
                            :subagent/abort abort-sub
                            ;; the web sidebar's rows (roomless, by session)
                            :session/subagent-stop    (session-sub-handler :subagent/abort)
                            :session/subagent-dismiss (session-sub-handler :subagent/dismiss)
                            :subagent/promote promote-sub
                            ;; The web Explain button (xi.web.views tool-post).
                            :subagent/explain-call explain-call
                            ;; Chained after the core resume handler by
                            ;; ext/merge-handlers — reseeds promoted stubs.
                            :session/resumed h/on-session-resumed)
   :fx               {:subagent/promote! promote-fx}
   ;; The sidebar lists a room's sub-agents with its buffers
   ;; (xi.server.room-manager/session-buffers on :lobby/state): refresh the
   ;; lobby when one appears, finishes or goes.
   :lobby-relevant   #{:subagent/spawn :subagent/turn-end :subagent/dismiss
                       :subagent/promoted}
   :roomless-events  #{:session/subagent-stop :session/subagent-dismiss}
   :commands         [{:name "subagents"
                       :description "View this room's background sub-agents"
                       :handler cmd-subagents}]})