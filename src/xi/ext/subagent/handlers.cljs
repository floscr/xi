(ns xi.ext.subagent.handlers
  "Pure, browser-safe state handlers for the sub-agents extension.

   Shared by both halves: the node ext (xi.ext.subagent — tools, gate, the
   turn-running :subagent/start effect) and the web ext (xi.ext.subagent.web —
   registers just these handlers so the mirrored :subagent/* broadcasts build
   the sub-agents' state client-side).

   State is room-scoped, so it rides in :room/joined snapshots and mirrors to
   every client:
     [:rooms rid :ext :subagents]
       {:agents [{:id :label :task :status :history [] :result
                  :started :ended :errored? :expanded?
                  :anchor :ended-anchor :after :call-id
                  :cli-session-id :session-id :upgraded?
                  :node :thread :question :closed? :exchanges}]}
   status: :running | :done | :error | :stopped
   anchor / ended-anchor: the room history's length at spawn / at the end,
   where the web timeline places the child's card and its result; :call-id
   is the spawn_subagent call that started it, :after the node of the entry
   it spawned after (stable anchors once the history is rebuilt from the
   transcript, xi.ext.subagent.threads/anchor-of).
   session-id: the hidden session the child was saved as when it finished
   (xi.ext.subagent save-fx); the entry is the parent's link to it and is
   saved with the parent chat (`persisted`).
   node / thread / question: a question thread about a block
   (xi.ext.subagent.threads).
   exchanges: once the user replies to a finished child (`reply`), its
   answers as [{:question :answer :status}], the first one the task's (no
   :question); :result stays the latest answer."
  (:require [clojure.string :as str]
            [xi.agent :as agent]
            [xi.core.state :as state]
            [xi.dialog :as dialog]
            [xi.util :as util]))

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

;; ── Nodes (xi.ext.subagent.threads finds them again) ────────────────────────

(def ^:private hint-chars 60)

(defn hint
  "The start of an entry's text: with its kind, what recognizes the entry
   in a rebuilt history."
  [{:keys [kind text tool error]}]
  (some-> (case kind
            (:user :text :thinking) text
            :tool-call (util/strip-mcp-prefix (str tool))
            :error (:message error)
            nil)
          str str/trim not-empty
          (#(subs % 0 (min hint-chars (count %))))))

(defn entry-node
  "The node of entry `i` of `history`; with `sub-id`, of that sub-agent's."
  ([history i] (entry-node nil history i))
  ([sub-id history i]
   (when-let [e (get (vec history) i)]
     (cond-> {:index i :kind (:kind e)}
       sub-id                    (assoc :sub-id sub-id)
       (= :tool-call (:kind e))  (assoc :call-id (:id e))
       (hint e)                  (assoc :hint (hint e))))))

(defn- spawn-point
  "The node of the entry a child spawned after: the history's last one but
   thinking, which a history rebuilt from the transcript drops."
  [history]
  (let [history (vec history)]
    (some #(when (not= :thinking (:kind (nth history %))) (entry-node history %))
          (range (dec (count history)) -1 -1))))

(defn final-text
  "The sub-agent's last assistant text entry since its latest prompt (a
   reply's :user entry) — the concise result the parent polls for."
  [history]
  (->> (rseq (vec history))
       (take-while #(not= :user (:kind %)))
       (some #(when (= :text (:kind %)) (:text %)))))

(def ^:private SUBAGENT_PREAMBLE
  (str "You are an autonomous SUB-AGENT spawned to handle one focused task in "
       "your own separate context. Work independently, use your tools as "
       "needed, and finish with a single concise message that captures the "
       "result — that final message is what the parent agent reads back. Do "
       "not ask the parent questions; make reasonable assumptions and proceed."))

;; ── Parent conversation ──────────────────────────────────────────────────────

(def ^:private context-recent-entries 12)
(def ^:private context-clips {:msg-chars 1500 :arg-chars 200})

(defn clip [s n]
  (let [s (str s)]
    (if (> (count s) n)
      (str (subs s 0 n) "\n… (" (- (count s) n) " more characters)")
      s)))

(defn- result-text [content]
  (if (sequential? content)
    (str/join "\n" (keep :text content))
    (str content)))

(defn entry-text
  "One history entry as transcript text, clipped per `clips`; a tool call's
   result only with :result-chars. nil for entries a reader skips (thinking)."
  [{:keys [kind text tool arguments is-error result error]}
   {:keys [msg-chars arg-chars result-chars]}]
  (case kind
    :user      (str "[user]\n" (clip text msg-chars))
    :text      (str "[assistant]\n" (clip text msg-chars))
    :tool-call (str "[tool call] " (util/strip-mcp-prefix (str tool)) " "
                    (clip (pr-str arguments) arg-chars)
                    (when is-error " → error")
                    (when (and result-chars (some? result))
                      (str "\n[result]\n" (clip (result-text result) result-chars))))
    :error     (str "[error] " (or (:message error) (pr-str error)))
    nil))

(defn- recent-context
  "A transcript excerpt of history `entries`: the last prompts and replies
   (clipped), tool calls as one-liners, thinking dropped."
  [entries]
  (->> entries
       (remove #(= :thinking (:kind %)))
       (take-last context-recent-entries)
       (keep #(entry-text % context-clips))
       (str/join "\n\n")))

;; The parent_history tool (registered for sub-agent turns only, xi.cli
;; subagent-opts; exec in xi.ext.subagent) reads the parent room's live
;; history, so it works for every provider and is current to the last event.
(def parent-history-tool "parent_history")

(def ^:private history-default-limit 40)
(def ^:private history-max-chars 30000)
(def ^:private history-clips {:msg-chars 6000 :arg-chars 600})

(defn- older-first-within-budget
  "The last entries of `lines` ([index text] pairs) that fit `limit` and the
   page's char budget — at least one — oldest first."
  [lines limit]
  (loop [acc '(), xs (rseq lines), size 0]
    (let [[_ t :as line] (first xs)]
      (if (or (nil? line)
              (>= (count acc) limit)
              (and (seq acc) (> (+ size (count t)) history-max-chars)))
        acc
        (recur (conj acc line) (next xs) (+ size (count t)))))))

(defn history-page
  "One parent_history page of room `history` (with `agent`, of that
   sub-agent's): the `limit` entries before index `before` (default: the
   end) whose text holds `query`, numbered by their history index, oldest
   first, then how to page back."
  [history {:keys [before limit query results agent]}]
  (let [clips  (cond-> history-clips results (assoc :result-chars 1500))
        q      (some-> query str/trim str/lower-case not-empty)
        end    (if (number? before) before (count history))
        lines  (into []
                     (keep-indexed
                      (fn [i e]
                        (when (< i end)
                          (when-let [t (entry-text e clips)]
                            (when (or (nil? q) (str/includes? (str/lower-case t) q))
                              [i t])))))
                     history)
        limit  (max 1 (int (or limit history-default-limit)))
        shown  (if (seq lines) (older-first-within-budget lines limit) ())
        first-i (ffirst shown)
        prefix (if agent (str agent "#") "#")]
    (if (empty? shown)
      (str "No entries" (when q (str " matching " (pr-str query)))
           (when (number? before) (str " before " prefix before)) ".")
      (str (if agent (str "Sub-agent " agent) "Parent conversation")
           ", entries " prefix first-i "–" prefix (first (last shown))
           " of " (count history) (when q (str " matching " (pr-str query)))
           ", oldest first:\n\n"
           (str/join "\n\n" (map (fn [[i t]] (str prefix i " " t)) shown))
           (when (< (count shown) (count lines))
             (str "\n\n… earlier: " parent-history-tool " with "
                  (when agent (str "agent " (pr-str agent) ", "))
                  "before " first-i))))))

(defn parent-context
  "The parent conversation for a sub-agent's system prompt: an excerpt of the
   room's history, pointing at parent_history for the rest, so whoever spawns
   one (the LLM, a button, an extension) never has to restate the context.
   nil without history."
  [room]
  (let [recent (recent-context (:history room))]
    (when (seq recent)
      (str "## The parent conversation\n"
           "The end of the conversation you were spawned from, most recent last. "
           "`" parent-history-tool "` reads all of it, tool calls included — use "
           "it when this excerpt doesn't cover what the task refers to.\n\n"
           recent))))

(defn child-system
  "System prompt for a sub-agent turn: the room's base system (AGENTS.md etc.)
   + the sub-agent preamble + the parent conversation (unless
   `parent-context?` is false) + any task-specific system text."
  [room {:keys [system parent-context?]}]
  (->> [(get-in room [:agent :system])
        SUBAGENT_PREAMBLE
        (when-not (false? parent-context?) (parent-context room))
        system]
       (remove str/blank?)
       (str/join "\n\n")))

;; ── Inline sub-agents ─────────────────────────────────────────────────────────
;; A tool block's Explain (`explain-call`), the question sub-agents (the
;; diff viewer's Ask and Explain, threads on blocks) and the diff viewer's
;; Modify are told by their id prefix.

(def ^:private explain-prefix "explain-")

(def ^:private ask-prefix "ask-")

(def ^:private modify-prefix "modify-")

(defn ask-sub-id []
  (gen-id "ask"))

(defn modify-sub-id
  "Id of a diff Modify sub-agent: inline like an Ask, but it edits files."
  []
  (gen-id "modify"))

(defn explain-sub-id
  "Sub-agent id of the explanation for tool call `call-id` — one per call."
  [call-id]
  (str explain-prefix call-id))

(defn- prefixed? [sub-id prefix]
  (and (string? sub-id) (str/starts-with? sub-id prefix)))

(defn inline-sub?
  "A sub-agent whose answer renders where it was asked (a tool block's
   Explain, the diff viewer's Ask / Modify) instead of as a sub-agent row
   (xi.web.views/subagent-row)."
  [sub-id]
  (boolean (some #(prefixed? sub-id %) [explain-prefix ask-prefix modify-prefix])))

;; ── State handlers (pure) ─────────────────────────────────────────────────────

(defn- start-effect
  "The :subagent/start effect of a turn of `child` in `room`: the provider
   settings come from the room's agent, the system from child-system with
   the child's spawn options (:system, :parent-context?). Its label names
   its permission asks; an Explain runs :unattended?, denying them (it
   explains an ask the user still has to answer), an Ask may edit but
   :confirm-edits? asks for every file edit (xi.ext.rules), a Modify asks
   like any sub-agent."
  [room child prompt images]
  [:subagent/start
   (cond-> {:room-id    (:id room)
            :sub-id     (:id child)
            :label      (:label child)
            :prompt     prompt
            :system     (child-system room child)
            :model      (get-in room [:agent :model])
            :provider   (get-in room [:agent :provider])
            :effort     (get-in room [:agent :effort])
            :cwd        (:cwd room)
            ;; An agent profile's tool allowlist binds sub-agents
            ;; too, but they always read their parent.
            :only-tools (some-> (get-in room [:agent :only-tools])
                                set
                                (conj parent-history-tool))}
     (seq images)                         (assoc :images images)
     (prefixed? (:id child) explain-prefix) (assoc :unattended? true)
     (prefixed? (:id child) ask-prefix)     (assoc :confirm-edits? true))])

(defn spawn
  "Append a sub-agent entry and kick off its turn. The child runs a FRESH
   context (no resume) that knows the parent conversation through its
   system prompt (child-system; `:parent-context? false` opts out) and the
   parent_history tool.

   `:anchor` is the room history's length at spawn time: the web timeline
   renders the child's card there (after the spawn_subagent call that
   started it), like answered dialogs. Clients apply the same mirrored event
   to the same history, so the anchor agrees everywhere.

   The event's :call-id / :node / :thread / :question / :selection go onto
   the child as they are (see the ns doc), :system / :parent-context? too,
   for `reply`.

   Emits the :subagent/start effect — the node build runs the turn; the web
   build registers a no-op for it (the turn only ever runs server-side).
   Needs the event's :sub-id: every client mirrors it (the node ext supplies
   a missing one)."
  [st {:keys [room-id sub-id task label prompt images] :as ev}]
  (when-let [room (and sub-id (state/get-room st room-id))]
    (let [child  (merge {:id      sub-id
                         :label   (or label (default-label task))
                         :task    task
                         :status  :running
                         :history []
                         :started (.now js/Date)
                         :anchor  (count (:history room))
                         :after   (spawn-point (:history room))}
                        (select-keys ev [:call-id :node :thread :question :selection
                                         :system :parent-context?]))]
      {:state   (update-in st [:rooms room-id :ext ext-id :agents]
                           (fnil conj []) child)
       :effects [(start-effect room child (or prompt task) images)]})))

(defn reply
  "Continue sub-agent `sub-id` with the user's `text`. While it runs, the
   text steers its turn (:subagent/steer, `steered` once taken). Finished,
   the same child runs another turn on its own conversation, still a child
   of the room, not a chat of its own. The node effect resumes the session it was
   saved as (:resume); sessionless providers replay :history, the task
   first. The prompt joins the history as a :user entry and :exchanges
   (see the ns doc). :ended-anchor stays until the turn ends, so the result
   post doesn't jump while the answer streams into it."
  [st {:keys [room-id sub-id text images] at :event/ts}]
  (let [text  (some-> text str/trim not-empty)
        room  (state/get-room st room-id)
        child (find-child st room-id sub-id)]
    (cond
      (not (and text room child)) nil

      (= :running (:status child))
      {:effects [[:subagent/steer {:room-id room-id :sub-id sub-id
                                   :text text :images images}]]}

      :else
      (let [prior (cond->> (vec (:history child))
                    (seq (:task child)) (into [{:kind :user :text (:task child)}]))]
        {:state   (update-child st room-id sub-id
                                (fn [c]
                                  (-> c
                                      (assoc :status :running :started (.now js/Date))
                                      (dissoc :ended :errored?)
                                      (update :exchanges
                                              #(conj (or % [{:answer (:result c)
                                                             :status (:status c)}])
                                                     {:question text}))
                                      (update :history (fnil conj [])
                                              (cond-> {:kind :user :text text} at (assoc :at at))))))
         :effects [(update (start-effect room child text images) 1 assoc
                           :resume (select-keys child [:cli-session-id :session-id])
                           :history prior)]}))))

(defn- steered
  "The running turn took a steering message (:subagent/steer): it joins the
   child's history where the turn stood."
  [st {:keys [room-id sub-id text images] at :event/ts}]
  (when (find-child st room-id sub-id)
    {:state (update-child st room-id sub-id
                          #(update % :history (fnil conj [])
                                   (cond-> {:kind :user :text text :steered? true}
                                     (seq images) (assoc :images images)
                                     at           (assoc :at at))))}))

(defn- text-delta [st {:keys [room-id sub-id text] at :event/ts}]
  (when (find-child st room-id sub-id)
    {:state (update-child st room-id sub-id
                          #(update % :history agent/fold-delta :text text at))}))

(defn- thinking-delta [st {:keys [room-id sub-id text] at :event/ts}]
  ;; Blank deltas → no entry; see xi.agent/thinking-delta.
  (when (and (find-child st room-id sub-id) (seq text))
    {:state (update-child st room-id sub-id
                          #(update % :history agent/fold-delta :thinking text at))}))

(defn- tool-start [st {:keys [room-id sub-id id tool arguments] at :event/ts}]
  (when (find-child st room-id sub-id)
    {:state (update-child st room-id sub-id
                          #(update % :history conj
                                   (cond-> {:kind :tool-call :id id :tool tool
                                            :arguments arguments :status :running}
                                     at (assoc :at at))))}))

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

(defn- turn-end
  "`:ended-anchor` is the room history's length when the child finished: the
   web timeline shows its result there, where the conversation was by then."
  [st {:keys [room-id sub-id aborted?]}]
  (when (find-child st room-id sub-id)
    (let [ended-anchor (count (get-in st [:rooms room-id :history]))]
      {:state (update-child st room-id sub-id
                            (fn [a]
                              (let [history (agent/finalize-history (:history a))
                                    result  (final-text history)
                                    status  (cond aborted?        :stopped
                                                  (:errored? a)   :error
                                                  :else           :done)]
                                (cond-> (assoc a
                                               :history history
                                               :ended  (.now js/Date)
                                               :ended-anchor ended-anchor
                                               :result result
                                               :status status)
                                  (seq (:exchanges a))
                                  (update :exchanges #(conj (pop %) (assoc (peek %)
                                                                           :answer result
                                                                           :status status)))))))})))

(defn- session-init
  "The provider reported the child's CLI session id — remember it so the
   sub-agent can later be promoted to a full session."
  [st {:keys [room-id sub-id cli-session-id]}]
  (when (find-child st room-id sub-id)
    {:state (update-child st room-id sub-id #(assoc % :cli-session-id cli-session-id))}))

(defn promoted
  "A sub-agent was saved as a (hidden) session: record its id on the child,
   whose entry is the parent's saved link to it (`persisted`)."
  [st {:keys [room-id sub-id session-id]}]
  (when (find-child st room-id sub-id)
    {:state (update-child st room-id sub-id #(assoc % :session-id session-id))}))

(defn upgraded
  "The child's session was made a regular one, listed with the other chats."
  [st {:keys [room-id sub-id]}]
  (when (find-child st room-id sub-id)
    {:state (update-child st room-id sub-id #(assoc % :upgraded? true))}))

;; ── Saved with the parent chat (:persist-room) ───────────────────────────────

(def ^:private saved-keys
  [:id :label :task :status :result :started :ended :errored?
   :anchor :ended-anchor :after :call-id :session-id :upgraded?
   :node :thread :question :closed? :exchanges])

(defn saved-child
  "A child as the parent chat saves it: the link block without its history,
   which lives in the child's own session. A child still running when saved
   can't be running once the chat is loaded again, so it is :stopped."
  [child]
  (cond-> (select-keys child saved-keys)
    (= :running (:status child)) (assoc :status :stopped)))

(defn persisted
  "The :persist-room fn of both halves: the agents list as saved children.
   Idempotent, as hydration filters the saved slice through it again."
  [slice]
  {:agents (mapv saved-child (:agents slice))})

(defn reset-for-session
  "Chained after :session/resumed and :session/created: the room now holds
   another conversation (or the same one reloaded), and the sub-agents of the
   one before were saved with it. A resume puts the loaded chat's back right
   after (:ext.persist/hydrate). Running ones stay so their turn can finish;
   /truncate (:keep-history?) keeps them all. Node-only: the web opens each
   session in its own room."
  [st {:keys [room-id keep-history?]}]
  (let [as (agents st room-id)]
    (when (and (seq as) (not keep-history?))
      {:state (assoc-in st [:rooms room-id :ext ext-id :agents]
                        (filterv #(= :running (:status %)) as))})))

(defn on-session-resumed
  "Rehydrate promoted sub-agent stubs from the resumed session's
   :promoted-subagents, the links of sessions saved before the agents list
   was saved with the chat (`persisted`). Hydration dedupes them by :id.

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

(defn- toggle-child
  "Expand/collapse one child's history in the web panel. Children default
   collapsed (:expanded? absent) so N running agents render only their heads;
   this opt-in flag streams a single child's full history on demand."
  [st {:keys [room-id sub-id]}]
  (when (find-child st room-id sub-id)
    {:state (update-child st room-id sub-id #(update % :expanded? not))}))


;; ── Explain a permission-gated tool call ─────────────────────────────────────
;; The web client's tool block grows an Explain button next to Allow/Deny.
;; It spawns an ordinary sub-agent whose id is derived from the tool call's
;; (explain-<call-id>), so the block finds its explanation in the mirrored
;; :agents list — streaming while it runs, kept after the ask is answered —
;; with no extra state. The chat's sub-agent rows and Agents list skip these (inline-sub?).


(defn dismiss
  "Remove finished sub-agents: the one named by :sub-id, or every
   non-running one when absent, except the inline ones (`inline-sub?`:
   threads and explanations, closed on their own). Running children are
   never dropped (they would keep streaming events into a missing entry). A
   dismissed child's link leaves the parent chat."
  [st {:keys [room-id sub-id]}]
  (when (seq (agents st room-id))
    {:state (update-in st [:rooms room-id :ext ext-id :agents]
                       (fn [as]
                         (filterv (fn [a]
                                    (or (= :running (:status a))
                                        (if sub-id
                                          (not= sub-id (:id a))
                                          (inline-sub? (:id a)))))
                                  as)))}))

(defn- earlier-turns
  "The `earlier` ([{:question :answer}]) turns of a diff thread for its next
   prompt, so a follow-up reads in context."
  [earlier]
  (when (seq earlier)
    (str "## Earlier in this thread\n"
         (str/join "\n\n" (map (fn [{q :question a :answer}]
                                 (str "**Q:** " q "\n\n**A:** " a))
                               earlier))
         "\n\n")))

(def ask-edit-policy
  "What an Ask sub-agent's prompt says about changing files: it may when
   asked to, and the user approves each edit (:confirm-edits?, start-effect)."
  (str "Don't change anything unless the user asks for a change. If they do, "
       "make it with the file edit tools (edit, write): the user is asked to "
       "approve each edit, so a denied one means leave it. Never run anything "
       "else with side effects."))

(defn ask-prompt
  "The diff Ask sub-agent's prompt. `earlier` ([{:question :answer}]) is the
   thread so far about the same lines, so a follow-up reads in context."
  [question snippet earlier]
  (str "The user is reading the diff of this session's changes and asks about "
       "the selected lines.\n\n"
       "```diff\n" snippet "\n```\n\n"
       (earlier-turns earlier)
       "## Question\n" question "\n\n"
       "Answer the question directly, in concise markdown. Read files in the "
       "working directory or the parent conversation (`" parent-history-tool
       "`) when the snippet doesn't say enough. " ask-edit-policy))

(defn diff-thread-prompt
  "The prompt of a turn in a diff viewer thread of `kind` about `snippet`:
   :ask a question (ask-prompt), :explain the lines (`text` an optional focus;
   follow-ups are questions), :modify a change to make in the files (`text`
   the instruction). `earlier` as in ask-prompt."
  [kind text snippet earlier]
  (case kind
    :modify
    (str "The user selected these lines in the diff viewer and wants them changed.\n\n"
         "```diff\n" snippet "\n```\n\n"
         (earlier-turns earlier)
         "## Change\n" text "\n\n"
         "Make this change in the working-tree files: `+` and context lines are "
         "the current code, `-` lines are already gone. Read the files first, "
         "edit only what the change needs, then reply with a short summary of "
         "what you changed.")

    :explain
    (if (seq earlier)
      (ask-prompt text snippet earlier)
      (str "The user is reading the diff of this session's changes and wants "
           "the selected lines explained.\n\n"
           "```diff\n" snippet "\n```\n\n"
           (when-let [focus (not-empty (some-> text str/trim))]
             (str "## Focus\n" focus "\n\n"))
           "Explain in plain language, in concise markdown, what these changes "
           "do and why. Read files in the working directory or the parent "
           "conversation (`" parent-history-tool "`) when the snippet doesn't "
           "say enough. Use read-only tools only: don't edit files or run "
           "anything with side effects."))

    (ask-prompt text snippet earlier)))

(defn ask-suggestions
  "One-tap questions for a diff selection whose lines have `types`
   (:add / :delete / :context)."
  [types]
  (let [changed (disj (set types) :context)]
    (cond
      (= #{:delete} changed) ["Why was this removed?" "Does anything still depend on it?"]
      (= #{:add} changed)    ["Why was this added?" "What could this break?"]
      (seq changed)          ["What does this change do?" "What could this break?"]
      :else                  ["What does this do?" "Where is this used?"])))

(defn find-explain
  "The explanation child of tool call `call-id` in `agents`, or nil."
  [agents call-id]
  (let [sub-id (explain-sub-id call-id)]
    (some #(when (= sub-id (:id %)) %) agents)))

(def ^:private edit-tools #{"edit" "write" "clj_replace"})

(defn modifications
  "The file changes `children` (sub-agents, oldest first) made: each edit /
   write call that went through as {:sub-id :id :path :text}, `text` the
   tool's own diff (xi.diff/tool-diff-body). A thread card steps through
   them (xi.web.views/thread-card)."
  [children]
  (vec (for [{sub-id :id history :history} children
             {:keys [kind id tool arguments result is-error status]} history
             :let [tool (str/lower-case (util/strip-mcp-prefix (str tool)))
                   text (some-> result util/extract-text-content util/strip-file-hash
                                str/trim not-empty)]
             :when (and (= :tool-call kind) (edit-tools tool) (= :done status)
                        (not is-error) text)]
         {:sub-id sub-id
          :id     id
          :path   (some #(or (get arguments %) (get arguments (name %)))
                        [:path :file_path :file])
          :text   text})))

(def ^:private explain-max-arg-chars 6000)
(def ^:private explain-max-diff-chars 6000)

(defn- format-arguments
  "`key: value` lines for a tool call's arguments (strings verbatim, so code
   and prose read naturally; the rest via pr-str), each value clipped."
  [arguments]
  (if (empty? arguments)
    "(no arguments)"
    (str/join "\n"
              (map (fn [[k v]]
                     (str (name k) ": " (clip (if (string? v) v (pr-str v))
                                              explain-max-arg-chars)))
                   arguments))))

(defn explain-prompt
  "The explanation sub-agent's prompt: the gated call, its ask, the parent
   conversation leading up to it; parent_history widens it when the excerpt
   isn't enough."
  [{:keys [entry dialog recent cwd]}]
  (let [diff (or (:diff entry) (:diff dialog))]
    (str
     "You are explaining a tool call to the user of a coding agent. The agent "
     "wants to run it; a permission prompt is holding it, and the user has to "
     "decide whether to allow or deny. Give them what they need to decide.\n\n"
     "## The call\n"
     "- tool: " (util/strip-mcp-prefix (str (:tool entry))) "\n"
     (when cwd (str "- working directory: " cwd "\n"))
     "- arguments:\n```\n" (format-arguments (:arguments entry)) "\n```\n"
     (when-let [m (or (:message dialog) (:text dialog))]
       (str "\n## The permission prompt\n" m "\n"))
     (when (seq (:text diff))
       (str "\n## The change it would make (" (:path diff) ")\n```diff\n"
            (clip (:text diff) explain-max-diff-chars) "\n```\n"))
     "\n## The conversation so far (most recent last)\n"
     (if (seq recent) recent "(no earlier messages)")
     "\n\n## Wider context\n"
     "`" parent-history-tool "` reads the whole parent conversation, tool calls "
     "included (`results: true` adds their output) — use it when the excerpt "
     "above doesn't say why the agent is doing this.\n"
     "You may read files in the working directory to see what the call "
     "touches. Use read-only tools only (read, grep, find, ls); never run the "
     "call yourself or anything with side effects — nobody is there to "
     "approve it.\n\n"
     "## Your answer\n"
     "Markdown, under 150 words, for a reader who did not watch the "
     "conversation:\n"
     "- **What it does** — concretely: which files, commands or services it "
     "touches and how.\n"
     "- **Why** — what the agent is trying to achieve, from the conversation.\n"
     "- **Risk** — reversible or not, anything destructive or surprising, "
     "anything that doesn't match what the user asked for.\n"
     "End with one line `**Recommendation:** allow` or "
     "`**Recommendation:** deny`, with a short reason. Output nothing else.")))

(defn- call-index [history call-id]
  (first (keep-indexed (fn [i e]
                         (when (and (= :tool-call (:kind e)) (= call-id (:id e))) i))
                       history)))

(defn- call-dialog
  "The pending :confirm dialog gating the tool call at `idx`, or nil."
  [dialogs history idx]
  (some (fn [d]
          (when (and (= :confirm (:type d))
                     (= idx (dialog/permission-tool-index d history 0)))
            d))
        dialogs))

(defn explain-call
  "Explain button on a tool block: spawn the call's explanation sub-agent.
   Pure, and a no-op on state — the spawn (and the dismissal of a failed or
   stopped earlier attempt) go out as :app/dispatch effects so they are
   broadcast and mirrored like any sub-agent event, and the web half can
   forward this event without applying it. nil when the call isn't in the
   history or its explanation is running or done."
  [st {:keys [room-id call-id]}]
  (when-let [room (state/get-room st room-id)]
    (let [history  (vec (:history room))
          idx      (call-index history call-id)
          existing (find-explain (agents st room-id) call-id)]
      (when (and idx (not (#{:running :done} (:status existing))))
        (let [entry  (nth history idx)
              dialog (call-dialog (get-in room [:ui :dialogs]) history idx)
              tool   (util/strip-mcp-prefix (str (:tool entry)))]
          {:effects (cond-> []
                      existing (conj [:app/dispatch {:type :subagent/dismiss
                                                     :room-id room-id
                                                     :sub-id (:id existing)}])
                      true     (conj [:app/dispatch
                                      {:type    :subagent/spawn
                                       :room-id room-id
                                       :sub-id  (explain-sub-id call-id)
                                       :label   (str "Explain " tool)
                                       :task    (str "Explain the " tool " call awaiting approval")
                                       ;; the prompt carries its own excerpt
                                       :parent-context? false
                                       :prompt  (explain-prompt
                                                 {:entry      entry
                                                  :dialog     dialog
                                                  :recent     (recent-context (subvec history 0 idx))
                                                  :cwd        (:cwd room)})}]))})))))

(def handlers
  "The pure state-updating handlers, shared by the node + web builds.
   :subagent/explain-call is registered by each half on its own: the web
   forwards it, the node applies it."
  {:subagent/spawn           spawn
   :subagent/reply           reply
   :subagent/steered         steered
   :subagent/text-delta      text-delta
   :subagent/thinking-delta  thinking-delta
   :subagent/tool-start      tool-start
   :subagent/tool-args       tool-args
   :subagent/tool-result     tool-result
   :subagent/error           sub-error
   :subagent/turn-end        turn-end
   :subagent/session-init    session-init
   :subagent/promoted        promoted
   :subagent/upgraded        upgraded
   :subagent/toggle-child    toggle-child
   :subagent/dismiss         dismiss})