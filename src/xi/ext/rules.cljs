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

   The tool-gate runs the hard-block first, then matches the first data rule and
   applies its action:

     :allow → force-allow, short-circuiting the remaining gates (via ext/allow)
     :deny  → intercept with an error result
     :nudge → intercept with a non-error steering result
     :ask   → raise a confirm dialog; on :always persist a session allow-rule

   The pure matcher lives in `xi.rules`; file/state loading + the imperative
   hard-block live in `xi.rules.store`."
  (:require [clojure.string :as str]
            [xi.ext.core :as ext]
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
  (let [m (if (= :mcp (:tool req))
            (cond-> {:tool :mcp}
              (:mcp-server req) (assoc :mcp-server (:mcp-server req))
              (:mcp-tool req)   (assoc :mcp-tool (:mcp-tool req)))
            (cond-> {:tool (:tool req)}
              ;; :other lumps every extension tool together — pin the exact
              ;; tool so [a]lways on spawn_subagent can't allow all of them.
              (and (= :other (:tool req)) (:tool-name req))
              (assoc :tool-name (:tool-name req))
              (:path req)    (assoc :path (:path req))
              (:command req) (assoc :command (:command req))))]
    {:match m :action {:type :allow}}))

(defn- allow-repo-rule-from-req
  "Build a session allow-rule scoped to the whole repo root of the target.
   Used when the user answers [r] (allow-repo) on an outside-repo :ask so later
   writes anywhere under that repo pass silently. write/edit are grouped (a
   write grant covers edits). Returns nil when the target isn't in any repo."
  [req]
  (when-let [repo (:repo req)]
    (let [t    (:tool req)
          tool (if (#{:write :edit} t) #{:write :edit} t)]
      {:match {:tool tool :repo repo} :action {:type :allow}})))

;; ── Recommend-a-rule flow ─────────────────────────────────────────────────────

(def ^:private rec-prefix "rules-rec-")

(def ^:private recommend-blocked-msg
  (str "Blocked — you asked xi to recommend a rule for this call, so it was not "
       "run. A recommendation is being prepared for the user; try the call again "
       "once they've decided."))

(defn- recommend-options
  "Append the recommend-a-rule option to a confirm's option list."
  [options]
  (let [opts (vec (or options [:yes :no :always]))]
    (cond-> opts
      (not (some #{:recommend-rule} opts)) (conj :recommend-rule))))

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
     "- {:type :ask   :message \"…\" :options [:yes :no :always]}  raise a confirm\n\n"
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
  (let [{:keys [tool tool-name path command mcp-server mcp-tool arguments]} req]
    (cond
      (= :mcp tool)
      (str "MCP tool call — approve?\n\n"
           "Server: " mcp-server "\n"
           "Tool:   " mcp-tool "\n\n"
           "Arguments:\n"
           (format-arguments arguments))

      command (str "Run " (name tool) " — approve?\n\n" command)
      path    (str (name tool) " " path " — approve?")

      ;; Extension tools (:other): name the tool and show what it will get.
      tool-name
      (str "Tool call — approve?\n\n"
           "Tool: " tool-name "\n\n"
           "Arguments:\n"
           (format-arguments arguments))

      :else   (str "Rule: " (name tool)))))

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

(defn- apply-action
  "Apply a matched rule's action to `tool-call`. Returns a tool-call (allow),
   nil (block), an intercepted result map, or a Promise of any of those."
  [{:keys [type message options]} tool-call req {:keys [confirm! dispatch! room-id]}]
  (case type
    :allow (ext/allow tool-call)
    :deny  (deny-result message)
    :nudge (nudge-result message)
    :ask   (if confirm!
             (-> (confirm! (or message (ask-message req))
                           (let [diff (ask-diff req)]
                             (cond-> {:options (recommend-options options)}
                               diff (assoc :diff diff))))
                 (.then (fn [ans]
                          (cond
                            (= ans :recommend)
                            (do (spawn-recommend! dispatch! room-id req message)
                                (deny-result recommend-blocked-msg))

                            ;; [a]lways → persist a narrow path/command allow-rule
                            (= ans :always)
                            (do (when dispatch!
                                  (dispatch! {:type    :ext.rules/add
                                              :room-id room-id
                                              :scope   :session
                                              :rule    (allow-rule-from-req req)}))
                                tool-call)

                            ;; [r] allow-repo → persist a repo-scoped allow-rule
                            ;; (falls back to a one-time allow when not in a repo)
                            (= ans :repo)
                            (do (when-let [rule (and dispatch!
                                                     (allow-repo-rule-from-req req))]
                                  (dispatch! {:type    :ext.rules/add
                                              :room-id room-id
                                              :scope   :session
                                              :rule    rule}))
                                tool-call)

                            ans   tool-call
                            :else nil))))
             tool-call)
    ;; unknown action type: allow through unchanged
    tool-call))

(defn tool-gate
  "The rules tool-gate. Runs the immutable hard-block first, then matches the
   first data rule and applies its action. No match → the call passes through
   unchanged so the remaining gates still run."
  [tool-call {:keys [get-state room-id cwd] :as ctx}]
  (or
   ;; 1. immutable, non-overridable
   (store/hard-block tool-call ctx)
   ;; 2. data rules, in precedence order
   (let [state   (when get-state (get-state))
         ruleset (store/ordered-rules state room-id cwd)
         req     (store/enrich-request (store/decision-request tool-call ctx) ruleset)
         rule    (rules/first-match ruleset req)]
     (if rule
       (apply-action (:action (rules/canonical rule)) tool-call req ctx)
       tool-call))))

;; ── Rule mutation (runtime scopes) ───────────────────────────────────────────

(defn add-rule
  "Add a runtime rule at :session (room-scoped, mirrors to clients) or :server
   (process-local) scope. New rules go to the front so the most recent grant
   wins among runtime rules."
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
  "Factory: the rules engine extension. Captures the dialog `ask!` (from
   ext/create-dialogs) so the recommend-a-rule flow can open its save dialog.
   On the client mirror (ask! nil) the recommend fx is a no-op."
  [{:keys [ask!]}]
  {:id       ext-id
   :init     {:room    {:rules []}
              :process {:rules []}}
   :handlers {:ext.rules/add     add-rule
              :subagent/turn-end on-subagent-turn-end}
   :tool-gate tool-gate
   :commands [{:name        "rules"
               :description "List or reload policy rules"
               :handler     rules-command
               :subcommands [{:name "list"   :description "List rules in precedence order"}
                             {:name "reload" :description "Clear the rules-file cache"}]}]
   :fx       {:rules/list           list-fx
              :rules/reload         reload-fx
              :rules/recommend-done (partial recommend-done-fx ask!)}})
