(ns xi.subagent
  "Sub-agent execution core — runs a background agent turn linked to a parent
   room, in its own fresh provider context so the sub-agent's work never enters
   the parent's conversation. Mirrors xi.agent/create-fx but streams into
   sub-agent-scoped state (keyed by parent room-id + sub-id) instead of the
   room history.

   A sub-agent is a 'process' the parent LLM starts and polls (see
   xi.ext.subagent): spawn → list/result → stop. Runtime handles (the abort fn)
   live in a process-local registry here — never in app state / over the wire,
   like process-manager's proc-handles. The provider tool ctx uses the PARENT
   room-id, so a sub-agent's tools (git, canvas_review_*, confirmations) act in
   the parent room; only its conversation is separate."
  (:require [xi.agent :as agent]))

;; sub-id -> {:abort! fn :room-id str}. Process-local; never crosses the wire.
(defonce ^:private registry (atom {}))

;; sub-id -> {:dir path :remove! fn} — each sub-agent's throwaway
;; CLAUDE_CONFIG_DIR, KEPT after its turn ends so the transcript inside stays
;; promotable to a real session (:subagent/promote, xi.ext.subagent). Cleaned
;; up on promotion (after the transcript is moved out) or room close.
(defonce ^:private dirs (atom {}))

(defn config-dir
  "The kept throwaway config dir of a sub-agent, or nil."
  [sub-id]
  (get-in @dirs [sub-id :dir]))

(defn cleanup-dir!
  "Delete a sub-agent's kept throwaway config dir (if any)."
  [sub-id]
  (when-let [{:keys [dir remove!]} (get @dirs sub-id)]
    (swap! dirs dissoc sub-id)
    (when remove! (remove! dir))))

(defn running?
  "True when a sub-agent turn is still in flight."
  [sub-id]
  (contains? @registry sub-id))

(defn abort!
  "Abort a running sub-agent by id. Returns true if it was running."
  [sub-id]
  (if-let [{f :abort!} (get @registry sub-id)]
    (do (when f (f)) true)
    false))

(defn- callbacks
  "Provider streaming callbacks → :subagent/* dispatches, folding into the
   sub-agent's own history (keyed by parent room-id + sub-id)."
  [dispatch! room-id sub-id]
  {:on-text        (fn [text] (dispatch! {:type :subagent/text-delta :room-id room-id :sub-id sub-id :text text}))
   :on-thinking    (fn [text] (dispatch! {:type :subagent/thinking-delta :room-id room-id :sub-id sub-id :text text}))
   :on-tool-start  (fn [{:keys [id name arguments]}]
                     (dispatch! {:type :subagent/tool-start :room-id room-id :sub-id sub-id
                                 :id id :tool name :arguments arguments}))
   :on-tool-args   (fn [{:keys [id arguments]}]
                     (dispatch! {:type :subagent/tool-args :room-id room-id :sub-id sub-id
                                 :id id :arguments arguments}))
   :on-tool-result (fn [{:keys [id content is-error]}]
                     (dispatch! {:type :subagent/tool-result :room-id room-id :sub-id sub-id
                                 :id id :content content :is-error is-error}))
   :on-session     (fn [session-id]
                     (dispatch! {:type :subagent/session-init :room-id room-id :sub-id sub-id
                                 :cli-session-id session-id}))
   :on-error       (fn [error]
                     (dispatch! {:type :subagent/error :room-id room-id :sub-id sub-id :error error}))})

(defn create-fx
  "Sub-agent provider effect. `providers` = provider-id → provider.
   `opts` is the same tooling threaded into xi.agent/create-fx:
     :tool-gate, :extra-tool-definitions, :extra-tool-registry, :ask!.

   The `:subagent/start` effect runs a fresh (never-resumed) provider turn with
   the same extension tools + gate the main agent gets, so a sub-agent can use
   git/read/etc. and its confirmations route to the parent room's dialogs."
  ([providers] (create-fx providers nil))
  ([providers {:keys [tool-gate extra-tool-definitions extra-tool-registry ask!
                      make-config-dir! remove-config-dir!]}]
   {:subagent/start
    (fn [{:keys [dispatch! get-state]}
         {:keys [room-id sub-id prompt system model provider cwd effort personal-agent?]}]
      (let [prov     (agent/resolve-provider providers {:provider provider :model model})
            ;; Run the sub-agent's turn against a throwaway CLAUDE_CONFIG_DIR so
            ;; the Claude CLI session it leaves behind lands in a temp dir, not
            ;; ~/.claude/projects — otherwise it leaks into the recent-sessions
            ;; list as a top-level chat. The dir is KEPT after the turn (see
            ;; `dirs`) so the transcript can later be promoted into a real,
            ;; resumable session when the user opens the sub-agent as a chat.
            config-dir (when make-config-dir! (make-config-dir!))
            ;; Tool ctx uses the PARENT room-id: the sub-agent's tools act in
            ;; the parent room (cwd, canvas state, confirm dialogs).
            gate-ctx {:dispatch! dispatch!
                      :get-state get-state
                      :room-id   room-id
                      :cwd       cwd
                      :sub-id    sub-id
                      :confirm!  (when ask!
                                   (fn confirm!
                                     ([message] (confirm! message nil))
                                     ([message copts]
                                      (ask! {:dispatch! dispatch! :state (get-state)}
                                            {:room-id room-id
                                             :dialog  (cond-> {:type :confirm :message message}
                                                        (:allow-always? copts) (assoc :allow-always? true))}))))}
            gate1    (when tool-gate (fn [tool-call] (tool-gate tool-call gate-ctx)))
            ;; The PARENT room's driving-client pid: chrome-mcp scopes a turn to
            ;; that client's terminal workspace. Without it a sub-agent's
            ;; browser calls would fall back to guessing a workspace and could
            ;; act where the *user* is looking (see xi.ext.chrome-mcp.guard).
            client-pid (agent/room-client-pid (get-state) room-id)
            {:keys [promise abort!]}
            ((:start-turn! prov)
             (cond-> (merge {:room-id room-id
                             :prompt  prompt
                             :model   model
                             :provider provider
                             :cwd     cwd
                             :effort  effort
                             :system  system
                             :personal-agent? personal-agent?}
                            (callbacks dispatch! room-id sub-id))
               gate1                  (assoc :tool-gate gate1)
               extra-tool-definitions (assoc :extra-tool-definitions extra-tool-definitions)
               extra-tool-registry    (assoc :extra-tool-registry extra-tool-registry)
               client-pid             (assoc :client-pid client-pid)
               config-dir             (assoc :env {"CLAUDE_CONFIG_DIR" config-dir})))]
        (swap! registry assoc sub-id {:abort! abort! :room-id room-id})
        (when config-dir
          (swap! dirs assoc sub-id {:dir config-dir :remove! remove-config-dir!}))
        (-> promise
            (.then
             (fn [result]
               (swap! registry dissoc sub-id)
               (dispatch! {:type :subagent/turn-end :room-id room-id :sub-id sub-id
                           :usage (:usage result) :cost (:cost result)
                           :aborted? (boolean (:aborted result))})))
            (.catch
             (fn [err]
               (swap! registry dissoc sub-id)
               (dispatch! {:type :subagent/error :room-id room-id :sub-id sub-id
                           :error {:type "error" :message (str (.-message err))}})
               (dispatch! {:type :subagent/turn-end :room-id room-id :sub-id sub-id
                           :aborted? false}))))))

    :subagent/abort
    (fn [_ {:keys [sub-id]}]
      (abort! sub-id))}))
