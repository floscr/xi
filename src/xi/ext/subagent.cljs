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
   every client; its shape is in xi.ext.subagent.handlers.

   A finished sub-agent is saved as a hidden session (`save-fx`, tagged
   :subagent-origin so listings skip it) and its entry, without the history,
   is saved with the parent chat (:persist-room): the parent's link block to
   the child session. Open as chat resumes it; upgrade makes it a regular,
   listed session. Question threads on blocks are sub-agents too
   (xi.ext.subagent.threads), so a chat and its sub-agents form a graph the
   agents walk by node address (parent_history `agent`, subagent_result
   `history`).

   The turn itself is run by the :subagent/start effect (xi.subagent, wired in
   xi.cli next to agent/create-fx). This namespace is the node data surface:
   tools, the turn-running effect wiring, system prompt. The pure state
   handlers live in xi.ext.subagent.handlers so the web half can reuse them."
  (:require [clojure.string :as str]
            [xi.commands :as commands]
            [xi.core.events :as events]
            [xi.core.state :as state]
            [xi.dialog :as dialog]
            [xi.ext.subagent.handlers :as h]
            [xi.ext.subagent.threads :as threads]
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
  "Open a sub-agent as a full chat: a resume of the session it was saved as
   when it finished (`save-fx`). One that has none yet (finished before
   sub-agents were saved at the end) is saved now."
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
      {:effects [[:subagent/save! {:room-id room-id :sub-id sub-id
                                   :open? open?}]]})))

(defn- open-asks
  "The permission asks sub-agent `sub-id` still waits on in its room."
  [st room-id sub-id]
  (filter #(= sub-id (:sub-id %)) (get-in st [:rooms room-id :ui :dialogs])))

(defn- turn-end
  "The shared turn-end, then save the child as a hidden session (`save-fx`).
   A turn stopped under a permission ask leaves it open: deny it, so it
   doesn't wait in the room for a turn that is gone."
  [st {:keys [room-id sub-id] :as ev}]
  (when-let [res ((:subagent/turn-end h/handlers) st ev)]
    (update res :effects (fnil into [])
            (concat
             (for [{:keys [id]} (open-asks st room-id sub-id)]
               [:app/dispatch {:type :ui/dialog-response :room-id room-id
                               :dialog-id id :value false}])
             [[:subagent/save! {:room-id room-id :sub-id sub-id}]]))))

(defn- upgrade
  "Make a saved child a regular session, listed with the other chats."
  [st {:keys [room-id sub-id]}]
  (when-let [sid (:session-id (find-child st room-id sub-id))]
    {:effects [[:subagent/upgrade! {:session-id sid}]]}))

(defn- upgrade-session
  "Roomless: the same from the child session itself (its header), by id."
  [_st {:keys [session-id]}]
  (when (string? session-id)
    {:effects [[:subagent/upgrade! {:session-id session-id}]]}))

(defn- save-fx
  "Save a finished sub-agent as a hidden session: move its transcript out of
   the throwaway config dir into a session tagged :subagent-origin (listings
   skip it), broadcast :subagent/promoted so the child's entry, the parent
   chat's saved link, carries its id, and with `open?` resume it into this
   room. Without a transcript (a provider that leaves none) the child keeps
   only its result; only an open reports that."
  [{:keys [dispatch! get-state]} {:keys [room-id sub-id open?]}]
  (let [st     (get-state)
        room   (get-in st [:rooms room-id])
        child  (find-child st room-id sub-id)
        cfg    (subagent/config-dir sub-id)
        saved  (when (and child cfg (:cli-session-id child) (not (:session-id child)))
                 (session/promote-subagent-session!
                  {:config-dir     cfg
                   :cwd            (:cwd room)
                   :cli-session-id (:cli-session-id child)
                   :label          (:label child)
                   :origin         {:session-id (get-in room [:session :id])
                                    :sub-id     sub-id}}))]
    (when saved
      (subagent/cleanup-dir! sub-id)
      (dispatch! {:type :subagent/promoted :room-id room-id :sub-id sub-id
                  :session-id (:id saved)}))
    (when open?
      ;; find-session-by-id sees hidden sessions
      (if-let [summary (some-> (or (:id saved) (:session-id child))
                               session/find-session-by-id)]
        (dispatch! {:type :session/resumed
                    :room-id room-id
                    :session (session/load-session summary)
                    :summary summary
                    :messages (session/read-session-messages summary)})
        (dispatch! {:type :ui/status :room-id room-id
                    :text "Couldn't open this sub-agent as a chat — no transcript found (non-Claude sub-agents can't be opened)."})))))

(defn- upgrade-fx
  "Clear a child session's :subagent-origin on disk, then tell the parent's
   live room (its link block), the child's live room (whose session map is
   saved back, so it must lose the marker too) and the lobby."
  [{:keys [dispatch! get-state]} {:keys [session-id]}]
  (when-let [summary (session/find-session-by-id session-id)]
    (let [origin (:subagent-origin summary)]
      (when (and origin (session/upgrade-subagent-session! summary))
        (let [st (get-state)]
          (when-let [rid (room-for-session st (:session-id origin))]
            (dispatch! {:type :subagent/upgraded :room-id rid :sub-id (:sub-id origin)}))
          (when-let [rid (room-for-session st session-id)]
            (dispatch! {:type :session/updated :room-id rid
                        :session {:subagent-origin nil}}))
          (dispatch! {:type :subagent/session-upgraded :session-id session-id}))))))

(defn- spawn
  "h/spawn, minting a missing :sub-id first. A spawn without one (/subagent,
   a queued prompt, /canvas-review) is dispatched again with one: clients
   mirror the event, so the id must travel in it rather than be minted by
   each process."
  [st {:keys [sub-id] :as ev}]
  (if-not sub-id
    {:effects [[:app/dispatch (assoc ev :sub-id (gen-id "sa"))]]}
    (h/spawn st ev)))

(defn- queued->subagent
  "Run the queued prompt at `index` in a sub-agent instead of after the
   current turn: drop it from the queue and spawn it. User-initiated (the web
   queue popover), so no spawn confirmation."
  [st {:keys [room-id index]}]
  (when-let [{:keys [text images]} (get (vec (get-in st [:rooms room-id :agent :queued]))
                                        index)]
    (let [text (str/trim (or text ""))]
      {:effects [[:app/dispatch {:type :prompt/queue-remove :room-id room-id :index index}]
                 [:app/dispatch (cond-> {:type :subagent/spawn :room-id room-id
                                         :task text :prompt text}
                                  (str/blank? text) (assoc :label "Queued prompt")
                                  (seq images)      (assoc :images images))]]})))

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
                                        :description "The task for the sub-agent. It is given an excerpt of this conversation and can read all of it, so refer to what was discussed instead of restating it."}
                                :label {:type "string"
                                        :description "Short label for the sub-agent in the UI (optional)."}}
                   :required ["task"]}}
   {:name "list_subagents"
    :description "List this room's sub-agents with their status, uptime, and a snippet of recent output."
    :input_schema {:type "object" :properties {} :required []}}
   {:name "subagent_result"
    :description (str "Fetch a sub-agent's result by its id. Returns the final "
                      "output once it is done, or a 'still running' notice with "
                      "recent output otherwise (call again later to poll). "
                      "With `history`, its work instead: messages and tool "
                      "calls numbered <id>#<n>, the newest first page.")
    :input_schema {:type "object"
                   :properties {:id      {:type "string" :description "The sub-agent id from spawn_subagent / list_subagents."}
                                :history {:type "boolean" :description "Read the sub-agent's history instead of its result."}
                                :before  {:type "number" :description "With history: only entries before this number."}}
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
            (map (fn [{:keys [id label status started ended node thread question session-id]
                       :as child}]
                   (let [dur (when started
                               (format-duration (- (or ended now) started)))
                         snip (recent-snippet child)]
                     (str "  • [" (name status) "] " id
                          (if node
                            (str " — the user asked about " (threads/address node)
                                 (when (not= thread id) (str " (thread " thread ")"))
                                 ": " (pr-str question))
                            (when label (str " — " label)))
                          (when dur (str " (" dur ")"))
                          (when session-id (str " [session " session-id "]"))
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

(defn- own-call-id
  "The id of the history entry of `call` ({:name :arguments}, the tool ctx's
   :call), the tool call running now, or nil."
  [st room-id call]
  (when call
    (let [history (vec (:history (state/get-room st room-id)))]
      (some->> (dialog/permission-tool-index {:call call} history 0)
               (nth history)
               :id))))

(defn- spawn-tool
  "Spawn the sub-agent and return its id. Approval is policy — the
   `::subagent-confirm` default rule asks before this ever runs. The spawn
   names the call that made it (:call-id), the card's anchor in a history
   rebuilt from the transcript."
  [{:keys [task label]} {:keys [dispatch! get-state room-id call]}]
  (let [sub-id  (gen-id "sa")
        label   (or (not-empty label) (default-label task))
        call-id (own-call-id (get-state) room-id call)]
    (dispatch! (cond-> {:type :subagent/spawn :room-id room-id
                        :sub-id sub-id :task task :label label :prompt task}
                 call-id (assoc :call-id call-id)))
    (spawn-result sub-id label)))

(defn- agent-history
  "Sub-agent `sub-id`'s history: in memory while the room holds it, else
   rebuilt from the session it was saved as (thinking blocks are not in the
   transcript, so the numbers can differ from the live ones). nil when there
   is neither."
  [st room-id sub-id]
  (let [child (find-child st room-id sub-id)]
    (cond
      (seq (:history child)) (:history child)
      (:session-id child)
      (some-> (session/find-session-by-id (:session-id child))
              session/read-session-messages
              commands/messages->history
              not-empty))))

(defn- agent-history-page
  "A history page of sub-agent `sub-id` (h/history-page `opts`), or why
   there is none."
  [st room-id sub-id opts]
  (if-let [history (agent-history st room-id sub-id)]
    (h/history-page history (assoc opts :agent sub-id))
    (str "No history for sub-agent " sub-id ".")))

(defn- result-tool [{:keys [id history before]} {:keys [get-state room-id]}]
  (let [st    (get-state)
        child (find-child st room-id id)]
    (cond
      (nil? child)
      (text-result (str "No sub-agent with id " id "."))

      history
      (text-result (agent-history-page st room-id id {:before before}))

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

;; ── Sub-agent-only tools (added to sub-agent turns by xi.cli/subagent-opts) ──

(defn- parent-history-exec
  "A sub-agent's tool ctx carries the PARENT room-id (xi.subagent), so this
   reads the parent's live history, or with `agent` a sibling's."
  [{:keys [agent] :as args} {:keys [get-state room-id]}]
  (let [st (get-state)]
    (text-result
     (if (seq agent)
       (agent-history-page st room-id agent (dissoc args :agent))
       (h/history-page (:history (state/get-room st room-id)) args)))))

(def child-tool-definitions
  [{:name h/parent-history-tool
    :description (str "Read the conversation of the agent that spawned you: "
                      "user and assistant messages and tool calls, numbered "
                      "by position (#12), oldest first. Without arguments: the "
                      "latest entries; page back with `before`. With `agent`, "
                      "the work of one of its sub-agents instead (sa-x#4).")
    :input_schema {:type "object"
                   :properties {:agent   {:type "string" :description "A sub-agent id (list_subagents, or sa-x in an address like sa-x#4): read its history instead."}
                                :before  {:type "number" :description "Only entries before this number (the hint at the end of a page). Default: up to the latest."}
                                :limit   {:type "number" :description "Max entries (default 40)."}
                                :query   {:type "string" :description "Only entries containing this text, case-insensitive."}
                                :results {:type "boolean" :description "Include tool call results, clipped."}}
                   :required []}}])

(def child-tool-registry
  {h/parent-history-tool (with-room-ctx parent-history-exec)})

;; ── Command (TUI visibility) ─────────────────────────────────────────────────

(defn- cmd-subagents
  "Open the live :subagents buffer (TUI: navigable pager; the web shows its
   inline panel regardless, so the buffer id just falls back to chat there)."
  [st {:keys [room-id]}]
  {:state (assoc-in st [:rooms room-id :ui :active-buffer] :subagents)})

(defn- cmd-subagent
  "/subagent <task>: spawn a sub-agent on the task, with the images attached
   to the command line (processed like a prompt's, then handed to the spawn).
   User-initiated, so no spawn confirmation; runs while the agent is busy too."
  [st {:keys [room-id args images]}]
  (let [task  (str/trim (or args ""))
        spawn (cond-> {:type :subagent/spawn :room-id room-id :task task :prompt task}
                (str/blank? task) (assoc :label "Attachment"))]
    (cond
      (and (str/blank? task) (empty? images))
      {:effects [[:app/dispatch {:type :ui/status :room-id room-id
                                 :text "Usage: /subagent <task>"}]]}

      (seq images)
      {:state   (assoc-in st [:rooms room-id :ui :pending-images] [])
       :effects [[:image/process {:room-id room-id :images images :event spawn}]]}

      :else
      {:effects [[:app/dispatch spawn]]})))

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
       "The sub-agent is given an excerpt of this conversation and can read "
       "all of it, so the task need not restate the context."))

;; ── Extension ─────────────────────────────────────────────────────────────────

(def extension
  {:id               ext-id
   :init             {:room {:agents []}}
   :persist-room     h/persisted
   :system-prompt    system-prompt
   :tool-definitions tool-defs
   :tool-registry    tool-registry
   :handlers         (assoc h/handlers
                            :subagent/spawn spawn
                            :subagent/turn-end turn-end
                            :room/close on-room-close
                            ;; The web queue popover's "Run in a sub-agent".
                            :subagent/run-queued queued->subagent
                            :subagent/abort abort-sub
                            ;; the web sidebar's rows (roomless, by session)
                            :session/subagent-stop    (session-sub-handler :subagent/abort)
                            :session/subagent-dismiss (session-sub-handler :subagent/dismiss)
                            :subagent/promote promote-sub
                            :subagent/upgrade upgrade
                            :session/subagent-upgrade upgrade-session
                            ;; The web Explain button (xi.web.views tool-post).
                            :subagent/explain-call h/explain-call
                            ;; A question about a block, and closing its thread.
                            :subagent/ask threads/ask
                            :subagent/thread-close threads/close
                            ;; Chained after the core handlers by
                            ;; ext/merge-handlers: the room's sub-agents belong
                            ;; to the chat before; a resume reseeds legacy links.
                            :session/resumed (events/chain h/reset-for-session
                                                           h/on-session-resumed)
                            :session/created h/reset-for-session)
   :fx               {:subagent/save!    save-fx
                      :subagent/upgrade! upgrade-fx}
   ;; The sidebar lists a room's sub-agents with its buffers
   ;; (xi.server.room-manager/session-buffers on :lobby/state): refresh the
   ;; lobby when one appears, finishes or goes, and when an upgraded session
   ;; joins the listings.
   :lobby-relevant   #{:subagent/spawn :subagent/turn-end :subagent/dismiss
                       :subagent/promoted :subagent/session-upgraded}
   :roomless-events  #{:session/subagent-stop :session/subagent-dismiss
                       :session/subagent-upgrade}
   :commands         [{:name "subagents"
                       :description "View this room's background sub-agents"
                       :handler cmd-subagents}
                      {:name "subagent" :aliases ["sa"]
                       :description "Run a task in a background sub-agent (/subagent <task>)"
                       :handler cmd-subagent}]})
