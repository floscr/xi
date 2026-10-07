(ns xi.ext.rules
  "Rules engine — a declarative, config-driven policy layer.

   A rule is a data map `{:match {…} :action {:type …} :scope …}` (with
   `:on-block`/`:do` accepted as aliases for `:match`/`:action`). Rules are
   loaded, in precedence order, from:

     1. immutable hard-block (agents may never write the rules files)
     2. repo config     <repo>/.xi/rules.edn
     3. global config   ~/.config/xi/rules.edn
     4. server-session  process-local [:ext :rules]
     5. session runtime room-scoped   [:rooms rid :ext :rules]

   Every tool call is decided here before it runs (`tool-policy`, called by
   core — xi.cli): the hard-block first, then the first matching data rule's
   action:

     :allow → run the tool
     :deny  → an error result instead of running it
     :nudge → a non-error steering result instead of running it
     :ask   → raise a confirm dialog; on :always persist a session allow-rule
     :hint  → never decides; its message is appended to the deciding rule's
              (deny / nudge / ask) text — see xi.rules/first-match

   There is no other policy hook: extensions can't gate tool calls.

   The pure matcher lives in `xi.rules`; file/state loading + the imperative
   hard-block live in `xi.rules.store`."
  (:require [clojure.string :as str]
            [xi.bb-trust :as bb-trust]
            [xi.dialog :as dialog]
            [xi.rules :as rules]
            [xi.rules.store :as store]
            [xi.tools.edit :as edit]
            [xi.tools.write :as write]))

(def ^:private ext-id store/ext-id)

(defn- deny-result [msg]
  {:intercepted true
   :result {:content [{:type "text" :text (or msg "Blocked by rule.")}]
            :is-error true}})

(defn- nudge-result [msg]
  {:intercepted true
   :result {:content [{:type "text" :text (or msg "")}]
            :is-error false}})

(defn- allow-rule-from-req
  "Build a session allow-rule from a decision request. For MCP calls it narrows
   to the specific server + tool; otherwise it matches the same tool and, when
   present, the same target path/command. Used when the user answers [a]lways on
   an :ask rule so the same call passes silently next time."
  [req]
  (let [m (case (:tool req)
            :mcp (cond-> {:tool :mcp}
                   (:mcp-server req) (assoc :mcp-server (:mcp-server req))
                   (:mcp-tool req)   (assoc :mcp-tool (:mcp-tool req)))
            ;; a network grant covers the host, not one exact URL
            :net (cond-> {:tool (:tool req)}
                   (:host req) (assoc :host (:host req)))
            (cond-> {:tool (:tool req)}
              ;; :other lumps every extension tool together — pin the exact
              ;; tool so [a]lways on spawn_subagent can't allow all of them.
              (and (= :other (:tool req)) (:tool-name req))
              (assoc :tool-name (:tool-name req))
              (:path req)    (assoc :path (:path req))
              (:command req) (assoc :command (:command req))))
        ;; a grant to one user extension never extends to the agent or to
        ;; other extensions
        m (cond-> m (:extension req) (assoc :extension (:extension req)))]
    {:match m :action {:type :allow}}))

(defn- allow-repo-rule-from-req
  "Build a session allow-rule scoped to the whole repo root of the target.
   Used when the user answers [r] (allow-repo) on an outside-repo :ask so later
   writes anywhere under that repo pass silently. write/edit are grouped (a
   write grant covers edits). Returns nil when the target isn't in any repo,
   or the call isn't a write/edit (a repo-wide bash/clj allow is no \"repo
   writes\" grant)."
  [req]
  (when-let [repo (and (#{:write :edit} (:tool req)) (:repo req))]
    {:match  (cond-> {:tool #{:write :edit} :repo repo}
               (:extension req) (assoc :extension (:extension req)))
     :action {:type :allow}}))

;; ── Recommend-a-rule flow ─────────────────────────────────────────────────────

(def ^:private rec-prefix "rules-rec-")

(def ^:private recommend-blocked-msg
  (str "Blocked — you asked xi to recommend a rule for this call, so it was not "
       "run. A recommendation is being prepared for the user; try the call again "
       "once they've decided."))

(defn- recommend-options
  "A confirm's option list for decision request `req`: the repo grant is
   dropped when there is no rule to save for it (see allow-repo-rule-from-req),
   and the recommend-a-rule option is appended when `recommend?` (the
   xi.config/recommend-rule? flag, threaded in as ctx :recommend-rule?) —
   otherwise it is removed, even if a rule's :options listed it."
  [options req recommend?]
  (let [opts (cond->> (vec (or options [:yes :no :always]))
               (not (allow-repo-rule-from-req req)) (into [] (remove #{:repo :allow-repo})))]
    (if recommend?
      (cond-> opts
        (not (some #{:recommend-rule} opts)) (conj :recommend-rule))
      (into [] (remove #{:recommend-rule}) opts))))

(defn- recommend-task
  "Build the sub-agent prompt: the guarded-call context + the rule schema,
   asking for a single recommended rule as EDN."
  [req message]
  (let [{:keys [tool path command effective-cwd repo mcp-server mcp-tool]} req]
    (str
     "You are xi's rules advisor. The user hit a guard prompt for a tool call "
     "and asked you to recommend a **rule** that would handle calls like it in "
     "the future.\n\n"
     "## The call that was guarded\n"
     "- tool: " (pr-str tool) "\n"
     (when (= :other tool) (str "- tool-name: " (:tool-name req) "\n"))
     (when path          (str "- path: " path "\n"))
     (when command       (str "- command: " command "\n"))
     (when mcp-server    (str "- mcp-server: " mcp-server "\n"))
     (when mcp-tool      (str "- mcp-tool: " mcp-tool "\n"))
     (when effective-cwd (str "- cwd: " effective-cwd "\n"))
     (when repo          (str "- repo: " repo "\n"))
     (when message       (str "- guard message: " message "\n"))
     "\n## Rule schema\n"
     "A rule is an EDN map `{:match {…} :action {:type …}}`.\n\n"
     "`:match` fields (all ANDed; omit a field to leave it unconstrained):\n"
     "- :tool     keyword or set of :write :edit :read :grep :find :ls :bash :clj :bb :mcp :other\n"
     "- :tool-name exact tool name (string/glob, regex, or set), for :other tools\n"
     "- :path     regex (partial, re-find) or glob string (full; * one segment, ** any, ? one char)\n"
     "- :command  regex (re-find) or string (substring) over the bash command / clj code\n"
     "- :repo     git-root path suffix of the target/effective cwd, e.g. \"config/dotfiles\"\n"
     "- :dir      absolute path prefix of the effective cwd (~ expanded)\n"
     "- :mcp-server / :mcp-tool  string/glob for mcp__<server>__<tool> calls\n\n"
     "`:action` types:\n"
     "- {:type :allow}                    force-allow, skip the remaining gates\n"
     "- {:type :deny  :message \"…\"}       block with an error\n"
     "- {:type :nudge :message \"…\"}       block with a non-error steering message\n"
     "- {:type :ask   :message \"…\" :options [:yes :no :always]}  raise a confirm\n"
     "- {:type :hint  :message \"…\"}       decides nothing; text appended to the deciding rule's message\n\n"
     "## Your task\n"
     "Recommend ONE rule that best fits this call. Prefer the narrowest match that "
     "still generalizes (match a repo or path pattern, not one exact file). Pick the "
     "action fitting the user's likely intent — usually :allow for a call they want "
     "permitted, or :ask/:nudge when judgement should remain.\n\n"
     "Reply with a one-line rationale, then the rule as a single EDN map in a "
     "```clojure fenced code block. Output nothing after the code block.")))

(defn- spawn-recommend!
  "Kick off a background sub-agent (tagged with `rec-prefix`) to draft a rule for
   the guarded call. Its turn-end triggers the recommendation dialog."
  [dispatch! room-id req message]
  (when dispatch!
    (let [sub-id (str rec-prefix (.now js/Date) "-" (rand-int 100000))]
      (dispatch! {:type    :subagent/spawn
                  :room-id room-id
                  :sub-id  sub-id
                  :label   "Rule recommendation"
                  :task    "Recommend a rule for a guarded tool call"
                  :prompt  (recommend-task req message)}))))

;; ── Ask-dialog message ────────────────────────────────────────────────────────

(defn- format-arg-value
  "Render one argument value: strings verbatim (paths/prose read naturally),
   everything else via pr-str."
  [v]
  (if (string? v) v (pr-str v)))

(defn- format-arguments
  "Indented `key: value` lines for a tool-call arguments map (placeholder when
   empty), so the confirm dialog shows exactly what the call will send."
  [arguments]
  (if (empty? arguments)
    "  (no arguments)"
    (str/join "\n"
              (map (fn [[k v]] (str "  " (name k) ": " (format-arg-value v)))
                   arguments))))

(defn- ask-message
  "Default confirm text for an :ask rule that supplies no explicit :message.
   MCP calls get the full server/tool/arguments block; bash/clj show the
   command; file tools show the path; other named tools show the tool name +
   arguments; anything else names the tool kind."
  [req]
  (let [{:keys [tool tool-name path command mcp-server mcp-tool arguments host]} req]
    (cond
      (= :net tool)
      (str "Network request — approve?\n\nHost: " host "\n\n" command)

      (= :mcp tool)
      (str "MCP tool call — approve?\n\n"
           "Server: " mcp-server "\n"
           "Tool:   " mcp-tool "\n\n"
           "Arguments:\n"
           (format-arguments arguments))

      ;; the bb-trust rule: say which bb.edn the [a]lways option would trust
      (false? (:bb-trusted? req))
      (str "bb.edn is not trusted ("
           (or (bb-trust/find-bb-edn (:effective-cwd req)) "none found")
           ") — run this task?\n\n" command)

      command (str "Run " (name tool) " — approve?\n\n" command)
      path    (str (name tool) " " path " — approve?")

      ;; Extension tools (:other): name the tool and show what it will get.
      tool-name
      (str "Tool call — approve?\n\n"
           "Tool: " tool-name "\n\n"
           "Arguments:\n"
           (format-arguments arguments))

      :else   (str "Rule: " (name tool)))))

(defn- with-requester
  "Name the user extension behind an xi.api.* request at the top of its
   confirm text — the user must know it isn't the agent asking."
  [req text]
  (if-let [ext (:extension req)]
    (str "[extension " (name ext) "] " text)
    text))

(defn- ask-diff
  "For a guarded write/edit, the diff the call would apply ({:path :text}), so
   the confirm dialog shows exactly what is being approved. nil for other tools
   or when there's nothing to preview."
  [{:keys [tool path arguments effective-cwd]}]
  (when path
    (let [ctx  {:cwd effective-cwd}
          text (case tool
                 :edit  (when (sequential? (:edits arguments))
                          (edit/preview arguments ctx))
                 :write (when (string? (:content arguments))
                          (write/preview arguments ctx))
                 nil)]
      (when (seq text)
        {:path path :text text}))))

(defn- doomed-edit
  "The error an :edit call would fail with, or nil — checked before asking, so
   the user isn't made to approve a call that can only error out."
  [{:keys [tool arguments effective-cwd]}]
  (when (and (= tool :edit) (sequential? (:edits arguments)))
    (edit/validate arguments {:cwd effective-cwd})))

(def ^:private option-events
  "Event types an ask option may carry (`{:value … :label … :event {:type …}}`
   in a rule's :options). A fixed allowlist: rules files are data anyone with
   repo access can write, and an option must not become a way to dispatch
   arbitrary events on a click."
  #{:ext.clj/trust-bb :mcp/trust})

(defn- option-event
  "The allowlisted event of the option in `options` the user answered with
   `ans`, or nil."
  [options ans]
  (some (fn [o]
          (when (and (map? o) (= ans (:value o))
                     (contains? option-events (get-in o [:event :type])))
            (:event o)))
        options))

(defn- apply-action
  "Apply a matched rule's action to decision request `req` → a decision (or a
   Promise of one, when it asks):
     {:decision :allow}             an :allow rule (force-allow)
     {:decision :approved}          an :ask answered yes / always / repo
     {:decision :unanswered}        an :ask with no confirm! to raise it
                                    (a deny instead when the action says
                                    `:unanswered :deny`)
     {:decision :deny :message m}   a :deny rule, or an :ask answered no
                                    (m nil, or the user's deny reason) /
                                    with recommend-a-rule
     {:decision :nudge :message m}  a :nudge rule
     {:decision :pass}              an unknown action type"
  [{:keys [type message options unanswered hints]} req {:keys [confirm! dispatch! room-id recommend-rule?]}]
  (let [deny-reason (volatile! nil)]
    (case type
      :allow {:decision :allow}
      :deny  {:decision :deny :message (rules/with-hints (or message "Blocked by rule.") hints)}
      :nudge {:decision :nudge :message (rules/with-hints (or message "") hints)}
      :ask   (if-let [error (and confirm! (doomed-edit req))]
               {:decision :deny :message error}
               (if confirm!
                 (-> ((dialog/capture-deny-reason confirm! deny-reason)
                      (with-requester
                       req
                       (rules/with-hints
                        (cond
                          (and message (:path req))
                          (store/with-path-target message (:path req) (:repo req))
                          message message
                          :else   (ask-message req))
                        hints))
                      (let [diff (ask-diff req)]
                        (cond-> {:options (recommend-options options req recommend-rule?)}
                          diff (assoc :diff diff))))
                     (.then (fn [ans]
                              (cond
                                (= ans :recommend)
                                (do (spawn-recommend! dispatch! room-id req message)
                                    {:decision :deny :message recommend-blocked-msg})

                                ;; [a]lways → persist a narrow path/command allow-rule
                                (= ans :always)
                                (do (when dispatch!
                                      (dispatch! {:type    :ext.rules/add
                                                  :room-id room-id
                                                  :scope   :session
                                                  :rule    (allow-rule-from-req req)}))
                                    {:decision :approved})

                                ;; [r] allow-repo → persist a repo-scoped allow-rule
                                ;; (falls back to a one-time allow when not in a repo)
                                (= ans :repo)
                                (do (when-let [rule (and dispatch!
                                                         (allow-repo-rule-from-req req))]
                                      (dispatch! {:type    :ext.rules/add
                                                  :room-id room-id
                                                  :scope   :session
                                                  :rule    rule}))
                                    {:decision :approved})

                                ;; an option carrying an event (the bb-trust
                                ;; rule's "trust bb.edn") → approve + dispatch it
                                (option-event options ans)
                                (do (when dispatch!
                                      (dispatch! (cond-> (assoc (option-event options ans)
                                                               :room-id room-id
                                                               :cwd (:effective-cwd req))
                                                   ;; the MCP trust option trusts the call's server
                                                   (:mcp-server req) (assoc :mcp-server (:mcp-server req)))))
                                    {:decision :approved})

                                ans   {:decision :approved}
                                :else {:decision :deny
                                       :message  (when-let [r @deny-reason]
                                                   (dialog/with-deny-reason
                                                    "The user denied this tool call." r))}))))
                 (if (= :deny unanswered)
                   {:decision :deny
                    :message  (str "Blocked: this call needs approval, but no client "
                                   "is attached to confirm it.")}
                   {:decision :unanswered})))
      {:decision :pass})))

(defn- decide*
  "`decide!`, but synchronous unless it has to ask (value or Promise)."
  [req {:keys [get-state room-id] :as ctx}]
  (if-let [msg (store/hard-block-request req)]
    {:decision :deny :message msg}
    (let [state   (when get-state (get-state))
          ruleset (store/ordered-rules state room-id (:effective-cwd req))
          req     (store/enrich-request req ruleset)
          rule    (rules/first-match ruleset req)]
      (if rule
        (apply-action (assoc (:action rule) :hints (:hints rule)) req ctx)
        {:decision :pass}))))

(defn decide!
  "Decide a decision request (store/decision-request for tool calls,
   store/request for anything else — e.g. xi.api.* calls from user
   extensions) through the rules engine: the immutable hard-block first, then
   the first matching rule's action. → Promise of a decision (see
   apply-action), or {:decision :pass} when no rule matches. ctx: {:get-state
   :room-id :confirm! :dispatch!}."
  [req ctx]
  (js/Promise.resolve (decide* req ctx)))

(defn tool-policy
  "The policy step every tool call passes before it runs (wired by xi.cli into
   the providers): `decide!` on the call → Promise of the tool call (run it),
   nil (blocked, no message), or {:intercepted true :result …} (the result to
   return instead). Allowed, approved, unmatched and unanswered calls run.
   Fails closed: if deciding throws, the call is denied."
  [tool-call ctx]
  (-> (js/Promise.resolve nil)
      (.then (fn [_] (decide* (store/decision-request tool-call ctx) ctx)))
      (.then (fn [{:keys [decision message]}]
               (case decision
                 :deny  (when message (deny-result message))
                 :nudge (nudge-result message)
                 tool-call)))
      (.catch (fn [e]
                (js/console.error "[rules] deciding a tool call failed:" e)
                (deny-result (str "Blocked: the rules engine failed on this call — "
                                  (.-message e)))))))

;; ── Rule mutation (runtime scopes) ───────────────────────────────────────────

(defn add-rule
  "Add a runtime rule at :session (room-scoped, mirrors to clients) or :server
   (process-local) scope. New rules go to the front so the most recent grant
   wins among runtime rules. The :session rules are persisted with the chat
   (`:persist-room`, xi.ext.persist), so an [Always] outlives the room and
   server restarts."
  [st {:keys [room-id scope rule]}]
  (let [path (if (= scope :server)
               [:ext ext-id :rules]
               [:rooms room-id :ext ext-id :rules])]
    {:state (update-in st path (fn [rs] (vec (cons rule (or rs [])))))}))

;; ── /rules command ───────────────────────────────────────────────────────────

(defn- status! [dispatch! room-id text]
  (dispatch! {:type :history/append :room-id room-id
              :entry {:kind :status :text text}}))

(defn- render-rules [ruleset]
  (if (empty? ruleset)
    (str "No rules configured.\n"
         "  global: ~/.config/xi/rules.edn\n"
         "  repo:   <repo>/.xi/rules.edn")
    (str "Rules (precedence order):\n"
         (str/join "\n"
                   (map-indexed
                    (fn [i r]
                      (let [r (rules/canonical r)]
                        (str "  " (inc i) ". [" (name (:scope r :?)) "] "
                             (pr-str (:match r)) " → " (pr-str (:action r)))))
                    ruleset)))))

(defn- list-fx [{:keys [dispatch! get-state]} {:keys [room-id]}]
  (let [state (when get-state (get-state))
        cwd   (get-in state [:rooms room-id :cwd])]
    (status! dispatch! room-id (render-rules (store/ordered-rules state room-id cwd)))))

(defn- reload-fx [{:keys [dispatch!]} {:keys [room-id]}]
  (store/clear-cache!)
  (status! dispatch! room-id "Rules cache cleared — files reloaded on next check."))

;; ── Recommendation result → editable save dialog ──────────────────────────

(defn- recommend-sub? [sub-id]
  (and (string? sub-id) (str/starts-with? sub-id rec-prefix)))

(defn on-subagent-turn-end
  "When a recommend sub-agent finishes (and wasn't aborted), fire the fx that
   parses its reply and opens the save dialog. Non-recommend sub-agents pass
   through untouched (nil = no change to the chained handler)."
  [_st {:keys [sub-id room-id aborted?]}]
  (when (and (not aborted?) (recommend-sub? sub-id))
    {:effects [[:rules/recommend-done {:room-id room-id :sub-id sub-id}]]}))

(defn extract-rule
  "Pull the recommended rule EDN out of a sub-agent's freeform reply: a
   ```clojure/```edn fenced block if present, else from the first `{`. Returns
   the parsed map, or nil when nothing parses to a map."
  [text]
  (when (string? text)
    (let [fenced (second (re-find #"(?s)```(?:clojure|edn)?\s*(.*?)```" text))
          body   (or fenced
                     (when-let [i (str/index-of text "{")]
                       (subs text i)))]
      (when body
        (let [v (store/read-rule-edn (str/trim body))]
          (when (map? v) v))))))

(defn save-recommended!
  "Persist an edited recommendation. `values` is the resolved :form map
   {:rule <edn-string> :scope <string>}. Runtime scopes (:session/:server) go
   through the :ext.rules/add event; config scopes (:repo/:global) are written to
   the on-disk rules file. Reports the outcome as a status line."
  [dispatch! room-id cwd values]
  (let [scope (-> (:scope values) str str/trim str/lower-case keyword)
        rule  (store/read-rule-edn (str/trim (str (:rule values))))]
    (cond
      (not (map? rule))
      (status! dispatch! room-id "Rule not saved — the edited text isn't a valid rule map.")

      (#{:session :server} scope)
      (do (dispatch! {:type :ext.rules/add :room-id room-id :scope scope
                      :rule (dissoc rule :scope)})
          (status! dispatch! room-id (str "Rule added at :" (name scope) " scope.")))

      (#{:repo :global} scope)
      (let [{:keys [file error]} (store/append-rule-file! scope cwd rule)]
        (status! dispatch! room-id
                 (cond
                   file  (str "Rule written to " file ".")
                   error (str "Rule not saved — " error)
                   :else (str "Couldn't resolve a " (name scope) " rules file."))))

      :else
      (status! dispatch! room-id
               (str "Unknown scope " (pr-str (:scope values))
                    " — use session, repo, global, or server.")))))

(defn- recommend-done-fx
  "Find the finished recommend sub-agent, parse its rule, and open an editable
   :form dialog (rule EDN + scope) for the user to save. Needs `ask!`; on the
   client mirror (ask! nil) it's a no-op."
  [ask! {:keys [dispatch! get-state] :as fx-ctx} {:keys [room-id sub-id]}]
  (when ask!
    (let [state  (get-state)
          cwd    (get-in state [:rooms room-id :cwd])
          agents (get-in state [:rooms room-id :ext :subagents :agents])
          child  (some #(when (= sub-id (:id %)) %) agents)
          rule   (extract-rule (:result child))]
      (if rule
        (-> (ask! fx-ctx
                  {:room-id room-id
                   :dialog {:type    :form
                            :message "Save recommended rule?"
                            :fields  [{:name :rule  :label "Rule (EDN)" :value (pr-str rule)}
                                      {:name :scope :label "Scope (session / repo / global / server)"
                                       :value "session"}]}})
            (.then (fn [values]
                     (when values
                       (save-recommended! dispatch! room-id cwd values)))))
        (status! dispatch! room-id
                 "Rule recommendation: couldn't parse a rule from the sub-agent's reply — open the Subagents buffer to see it.")))))

(defn- rules-command [_st {:keys [room-id args]}]
  (let [sub (str/lower-case (str/trim (or args "")))]
    (case sub
      "reload" {:effects [[:rules/reload {:room-id room-id}]]}
      {:effects [[:rules/list {:room-id room-id}]]})))

(defn create
  "Factory: the rules engine's extension half — rule state, /rules and the
   recommend-a-rule flow. Deciding tool calls is not an extension surface:
   core calls `tool-policy` directly. Captures the dialog `ask!` (from
   ext/create-dialogs) so the recommend-a-rule flow can open its save dialog.
   On the client mirror (ask! nil) the recommend fx is a no-op."
  [{:keys [ask!]}]
  {:id       ext-id
   :init     {:room    {:rules []}
              :process {:rules []}}
   ;; Always-answers are session rules: they ride along with the chat across
   ;; server restarts and reaped rooms.
   :persist-room true
   :handlers {:ext.rules/add     add-rule
              :subagent/turn-end on-subagent-turn-end}
   :commands [{:name        "rules"
               :description "List or reload policy rules"
               :handler     rules-command
               :subcommands [{:name "list"   :description "List rules in precedence order"}
                             {:name "reload" :description "Clear the rules-file cache"}]}]
   :fx       {:rules/list           list-fx
              :rules/reload         reload-fx
              :rules/recommend-done (partial recommend-done-fx ask!)}})
