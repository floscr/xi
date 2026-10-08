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

(defn dismiss
  "Remove finished sub-agents from the panel: the one named by :sub-id, or
   every non-running one when absent. Running children are never dropped (they
   would keep streaming events into a missing entry); the panel hides itself
   once the list is empty."
  [st {:keys [room-id sub-id]}]
  (when (seq (agents st room-id))
    {:state (update-in st [:rooms room-id :ext ext-id :agents]
                       (fn [as]
                         (filterv (fn [a]
                                    (or (= :running (:status a))
                                        (and sub-id (not= sub-id (:id a)))))
                                  as)))}))

;; ── Explain a permission-gated tool call ─────────────────────────────────────
;; The web client's tool block grows an Explain button next to Allow/Deny.
;; It spawns an ordinary sub-agent whose id is derived from the tool call's
;; (explain-<call-id>), so the block finds its explanation in the mirrored
;; :agents list — streaming while it runs, kept after the ask is answered —
;; with no extra state. The Sub-agents panel hides these (explain-sub?).

(def ^:private explain-prefix "explain-")

(defn explain-sub-id
  "Sub-agent id of the explanation for tool call `call-id` — one per call."
  [call-id]
  (str explain-prefix call-id))

(defn explain-sub?
  "An explanation sub-agent (the web Explain button's)? Those render under
   their tool block instead of in the Sub-agents panel."
  [sub-id]
  (and (string? sub-id) (str/starts-with? sub-id explain-prefix)))

(defn find-explain
  "The explanation child of tool call `call-id` in `agents`, or nil."
  [agents call-id]
  (let [sub-id (explain-sub-id call-id)]
    (some #(when (= sub-id (:id %)) %) agents)))

(def ^:private explain-max-arg-chars 6000)
(def ^:private explain-max-diff-chars 6000)
(def ^:private explain-max-msg-chars 1500)
(def ^:private explain-recent-entries 12)

(defn- clip [s n]
  (let [s (str s)]
    (if (> (count s) n)
      (str (subs s 0 n) "\n… (" (- (count s) n) " more characters)")
      s)))

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

(defn- recent-context
  "The parent conversation before the call as a transcript excerpt: the last
   prompts and replies (clipped), earlier tool calls as one-liners, thinking
   dropped."
  [entries]
  (->> entries
       (remove #(= :thinking (:kind %)))
       (take-last explain-recent-entries)
       (keep (fn [{:keys [kind text tool arguments is-error]}]
               (case kind
                 :user      (str "[user]\n" (clip text explain-max-msg-chars))
                 :text      (str "[assistant]\n" (clip text explain-max-msg-chars))
                 :tool-call (str "[tool call] " (util/strip-mcp-prefix (str tool)) " "
                                 (clip (pr-str arguments) 200)
                                 (when is-error " → error"))
                 nil)))
       (str/join "\n\n")))

(defn explain-prompt
  "The explanation sub-agent's prompt: the gated call, its ask, the parent
   conversation leading up to it, and where the full transcript lives so the
   sub-agent can widen its context when the excerpt isn't enough."
  [{:keys [entry dialog recent cwd transcript]}]
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
     (if transcript
       (str "The full parent transcript is the JSONL file " transcript
            " — read or grep it when the excerpt above doesn't say why the "
            "agent is doing this.\n")
       "There is no transcript on disk; work from the excerpt above.\n")
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
   history or its explanation is running or done. `:transcript` is the parent
   transcript path, added by the node ext (xi.ext.subagent)."
  [st {:keys [room-id call-id transcript]}]
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
                                       :prompt  (explain-prompt
                                                 {:entry      entry
                                                  :dialog     dialog
                                                  :recent     (recent-context (subvec history 0 idx))
                                                  :cwd        (:cwd room)
                                                  :transcript transcript})}]))})))))

(def handlers
  "The pure state-updating handlers, shared by the node + web builds.
   :subagent/explain-call is registered by each half on its own: the web
   forwards it, the node adds the transcript path (see explain-call)."
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
   :subagent/toggle-child    toggle-child
   :subagent/dismiss         dismiss})
