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
  (:require [xi.agent :as agent]
            [xi.core.state :as state]
            [xi.session :as session]))

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
     :tool-policy, :extra-tool-definitions, :extra-tool-registry.

   The `:subagent/start` effect runs a fresh (never-resumed) provider turn with
   the same extension tools + rules policy the main agent gets, so a sub-agent
   can use git/read/etc. A background sub-agent runs unattended, so rule
   confirmations resolve to the safe default (deny) instead of opening an
   interactive dialog in the parent room: an unanswered dialog would block the
   sub-agent's turn forever (it never reaches :subagent/turn-end and stays
   stuck :running). Tools the user already [a]llow-always'd in the parent room
   skip :confirm! entirely (room-scoped allowlist), so those still run."
  ([providers] (create-fx providers nil))
  ([providers {:keys [tool-policy extra-tool-definitions extra-tool-registry
                      make-config-dir! remove-config-dir!]}]
   {:subagent/start
    (fn [{:keys [dispatch! get-state]}
         {:keys [room-id sub-id prompt system model provider cwd effort only-tools]}]
      (let [prov     (agent/resolve-provider providers {:provider provider :model model})
            ;; Run the sub-agent's turn against a throwaway CLAUDE_CONFIG_DIR so
            ;; the Claude CLI session it leaves behind lands in a temp dir, not
            ;; ~/.claude/projects — otherwise it leaks into the recent-sessions
            ;; list as a top-level chat. The dir is KEPT after the turn (see
            ;; `dirs`) so the transcript can later be promoted into a real,
            ;; resumable session when the user opens the sub-agent as a chat.
            config-dir (when make-config-dir! (make-config-dir!))
            ;; Tool ctx uses the PARENT room-id: the sub-agent's tools act in
            ;; the parent room (cwd, canvas state, confirm dialogs). Handed to
            ;; the tool policy and, as :tool-ctx, to tool exec-fns.
            tool-ctx {:dispatch! dispatch!
                      :get-state get-state
                      :room-id   room-id
                      :cwd       cwd
                      ;; acts for whoever's prompt the parent turn answers
                      :user      (state/turn-user (get-state) room-id)
                      :sub-id    sub-id
                      ;; Auto-deny (never open a dialog): a background sub-agent
                      ;; has no interactive operator in its own turn, so a
                      ;; routed-and-awaited confirm would hang it indefinitely.
                      ;; MUST stay present-and-denying — a nil :confirm! makes
                      ;; rules :ask actions *pass through*, i.e. auto-ALLOW
                      ;; guarded ops + third-party MCP calls.
                      :confirm!  (fn confirm!
                                   ([_message] (js/Promise.resolve false))
                                   ([_message _copts] (js/Promise.resolve false)))}
            policy1    (when tool-policy (fn [tool-call] (tool-policy tool-call tool-ctx)))
            ;; The PARENT room's driving-client pid, so a sub-agent's MCP calls
            ;; carry the same _meta "xi/clientPid" as the parent's (a browser
            ;; server scopes to that terminal, see xi.ext.mcp/call-meta).
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
                             :tool-ctx tool-ctx}
                            (callbacks dispatch! room-id sub-id))
               policy1                  (assoc :tool-policy policy1)
               only-tools               (assoc :only-tools only-tools)
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
                           :aborted? false})))
            ;; The kept config dir outlives the turn; save a login refreshed
            ;; during it now, not at cleanup (see session/sync-credentials-back!).
            (.finally
             (fn [] (when config-dir (session/sync-credentials-back! config-dir)))))))

    :subagent/abort
    (fn [_ {:keys [sub-id]}]
      (abort! sub-id))}))
