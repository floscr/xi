(ns xi.providers.anthropic
  "Anthropic provider — runs the Claude Agent SDK in a separate runner
   process (packages/providers/anthropic/runner.mjs, its own node_modules, freely
   upgradable SDK) driven through xi.providers.runner. The host owns the tool
   registry + permission gate; the runner proxies each tool call back via
   `tool-call` frames.

   Interface: (stream-messages-runner opts) → {:promise :abort!}.
   The policy step is injected via :tool-policy (async; nil blocks,
   {:intercepted true :result …} stands in for the tool's result) — no
   dependency on the rules engine here."
  (:require ["node:fs" :as fs]
            [clojure.string :as str]
            [xi.providers.runner :as runner]
            [xi.tools.registry :as tools]
            [xi.util :as util]))

(defn- cwd-missing?
  "True when a working directory is set but doesn't exist on this host. A Pi
   session (cwd=/var/lib/xi) opened on a machine without that dir makes Node's
   spawn throw ENOENT, which the SDK mislabels as 'executable not found';
   detecting the missing dir lets the agent recover instead."
  [cwd]
  (and (string? cwd)
       (pos? (count cwd))
       (try (not (.existsSync fs cwd)) (catch :default _ false))))

;; ── MCP Tool Bridge ───────────────────────────────────────────────────────────
;;
;; Claude's built-in tools are disabled (tools: [] whitelist) — Xi exposes
;; its own via MCP. Each call passes through :tool-policy before execution.

(def ^:private MCP_SERVER_NAME "xi-tools")
(def ^:private MCP_TOOL_PREFIX (str "mcp__" MCP_SERVER_NAME "__"))

(def ^:private TOOL_NAMING_NOTE
  ;; Counter the model's trained Claude-Code prior to call bare native tool
  ;; names. Native builtins are disabled (:tools []); only mcp__xi-tools__*
  ;; exist, so a bare `grep`/`find`/`bash` call is rejected by the SDK with
  ;; "No such tool available". Weaker models fall back to the bare names
  ;; without this reminder.
  (str "IMPORTANT — tool names in THIS environment: the native Claude Code "
       "tools (Bash, Grep, Glob, Read, Edit, Write, LS, Task, WebFetch, etc.) "
       "are DISABLED. Every tool is provided by the `" MCP_SERVER_NAME "` MCP "
       "server and MUST be called by its fully-qualified name — the tool name "
       "prefixed with `" MCP_TOOL_PREFIX "` (e.g. `" MCP_TOOL_PREFIX "grep`, `"
       MCP_TOOL_PREFIX "find`, `" MCP_TOOL_PREFIX "read`, `" MCP_TOOL_PREFIX
       "edit`, `" MCP_TOOL_PREFIX "clj`). Calling a bare name such as `grep`, "
       "`find`, or `bash` fails with \"No such tool available\" — always use the "
       "prefixed name shown in each tool's schema."))

(def ^:private default-policy
  "Pass-through tool policy (xi.cli injects the rules engine)."
  (fn [tool-call] (js/Promise.resolve tool-call)))

(defn run-gated-tool
  "Execute one tool call through the tool policy, then the registry.
   Returns a Promise of #js {:content … :isError …}. Services the runner's
   proxied tool-call frames, so the permission gate + registry stay host-side
   regardless of transport.

   opts: {:tool-name :exec-fn :arguments :tool-policy :tool-ctx :cwd :client-pid}.
   The exec-fn's ctx is the per-turn :tool-ctx (dispatch!, get-state, room-id,
   confirm! — see xi.agent/create-fx) with :cwd / :client-pid on top."
  [{:keys [tool-name exec-fn arguments tool-policy tool-ctx cwd client-pid]}]
  (let [tool-policy (or tool-policy default-policy)
        tool-call {:name tool-name :arguments arguments}]
    (-> (tool-policy tool-call)
        (.then
         (fn [gated]
           (cond
             (nil? gated)
             #js {:content #js [#js {:type "text"
                                     :text "Blocked by Xi permission gate"}]
                  :isError true}

             (:intercepted gated)
             (let [result (:result gated)]
               #js {:content (clj->js (util/cap-tool-result-content (:content result)))
                    :isError (boolean (:is-error result))})

             :else
             (-> (tools/run-tool exec-fn (or (:arguments gated) arguments)
                                 (assoc tool-ctx :cwd cwd :client-pid client-pid)
                                 tool-call)
                 (.then (fn [{:keys [content is-error]}]
                          #js {:content (clj->js content)
                               :isError is-error})))))))))

(defn tool-dispatcher
  "Return a fn `(tool-name clj-args) → Promise<#js {:content :isError}>` that
   runs a tool through the gate + registry. Used by the runner transport to
   service proxied `tool-call` frames on the host."
  [{:keys [registry tool-policy tool-ctx cwd client-pid]}]
  (fn [tool-name arguments]
    (run-gated-tool {:tool-name tool-name
                     :exec-fn (get registry tool-name)
                     :arguments arguments
                     :tool-policy tool-policy
                     :tool-ctx tool-ctx
                     :cwd cwd
                     :client-pid client-pid})))

;; ── Stream Event Processing ──────────────────────────────────────────────────

(defn map-stop-reason [reason]
  (case reason
    "tool_use"   "toolUse"
    "max_tokens" "length"
    "end_turn"   "stop"
    "stop"))

(defn- try-json-parse [s]
  (try (js->clj (js/JSON.parse s) :keywordize-keys true)
       (catch :default _ nil)))

(defn parse-partial-json
  "Best-effort parse of a streaming JSON prefix (a tool's `input_json_delta`
   chunks so far): close the open string and containers, and if that still
   isn't valid drop a trailing comma / dangling key / half-written scalar
   member. Returns the parsed value, or nil when the prefix can't be
   completed yet. Display-only — the authoritative arguments are the full
   parse at `content_block_stop`."
  [s]
  (when (seq s)
    (let [n (count s)
          [stack in-str? esc?]
          (loop [i 0 stack [] in-str? false esc? false]
            (if (< i n)
              (let [c (.charAt s i)]
                (cond
                  esc?    (recur (inc i) stack true false)
                  in-str? (case c
                            "\\" (recur (inc i) stack true true)
                            "\"" (recur (inc i) stack false false)
                            (recur (inc i) stack true false))
                  :else   (case c
                            "\""      (recur (inc i) stack true false)
                            ("{" "[") (recur (inc i) (conj stack c) false false)
                            ("}" "]") (recur (inc i) (if (seq stack) (pop stack) stack) false false)
                            (recur (inc i) stack false false))))
              [stack in-str? esc?]))
          body    (cond-> s
                    ;; a dangling escape: drop `\` or a partial `\uXX`
                    esc?    (subs 0 (dec n))
                    in-str? (str/replace #"\\u[0-9a-fA-F]{0,3}$" ""))
          base    (cond-> body in-str? (str "\""))
          closers (apply str (map {"{" "}" "[" "]"} (rseq stack)))]
      (some #(try-json-parse (str % closers))
            [base
             (str/replace base #"[\s,]+$" "")
             (str/replace base #",?\s*\"(?:[^\"\\]|\\.)*\"\s*:?\s*$" "")
             (str/replace base #",?\s*\"(?:[^\"\\]|\\.)*\"\s*:\s*[^\s\"{}\[\],]+$" "")]))))

(def ^:private partial-args-interval-ms
  "Min gap between streamed partial-argument updates for one tool call. Each
   update is a dispatch + WS broadcast of the args so far, so a long input
   (a big clj snippet, a file write) is throttled rather than sent per token."
  100)

(defn- stream-partial-args!
  "Fold an `input_json_delta` chunk into the pending tool input and, at most
   every `partial-args-interval-ms`, surface the args parsed so far via
   :on-tool-args — so a running tool block shows its input while the model is
   still writing it instead of sitting empty until `content_block_stop`."
  [callbacks state idx chunk]
  (let [pending (-> (swap! state update-in [:pending-tool-inputs idx :json-chunks] conj chunk)
                    (get-in [:pending-tool-inputs idx]))
        now     (js/Date.now)]
    (when (and (:on-tool-args callbacks)
               (:id pending)
               (>= (- now (:emitted-at pending 0)) partial-args-interval-ms))
      (when-let [args (parse-partial-json (apply str (:json-chunks pending)))]
        (when (and (map? args) (seq args))
          (swap! state assoc-in [:pending-tool-inputs idx :emitted-at] now)
          ((:on-tool-args callbacks)
           {:id (:id pending) :name (:name pending) :arguments args}))))))

(defn- process-stream-event
  [^js event callbacks state]
  (let [event-type (.-type event)]
    (case event-type
      "message_start"
      (when-let [usage (some-> event .-message .-usage)]
        (swap! state assoc :usage (js->clj usage :keywordize-keys true)))

      "content_block_start"
      (let [^js block (.-content_block event)
            block-type (.-type block)
            idx (.-index event)]
        (when (= "tool_use" block-type)
          (let [id (.-id block)
                tool-name (util/strip-mcp-prefix (.-name block))
                input (or (js->clj (.-input block) :keywordize-keys true) {})]
            (swap! state (fn [s]
                           (-> s
                               (update :tool-call-ids conj id)
                               (assoc-in [:pending-tool-inputs idx]
                                         {:id id :name tool-name :json-chunks []}))))
            (when (:on-tool-start callbacks)
              ((:on-tool-start callbacks)
               {:id id :name tool-name :arguments input})))))

      "content_block_delta"
      (let [^js delta (.-delta event)
            delta-type (.-type delta)
            idx (.-index event)]
        (case delta-type
          "text_delta"     (when (:on-text callbacks) ((:on-text callbacks) (.-text delta)))
          "thinking_delta" (when (:on-thinking callbacks) ((:on-thinking callbacks) (.-thinking delta)))
          "input_json_delta"
          (stream-partial-args! callbacks state idx (.-partial_json delta))
          nil))

      "content_block_stop"
      (let [idx (.-index event)
            pending (get-in @state [:pending-tool-inputs idx])]
        (when pending
          (let [json-str (apply str (:json-chunks pending))
                args (try (js->clj (js/JSON.parse json-str) :keywordize-keys true)
                          (catch :default _ {}))]
            (when (:on-tool-args callbacks)
              ((:on-tool-args callbacks)
               {:id (:id pending) :name (:name pending) :arguments args}))
            (swap! state update :pending-tool-inputs dissoc idx))))

      "message_delta"
      (let [^js delta (.-delta event)
            stop-reason (.-stop_reason delta)
            ^js usage (.-usage event)]
        (when stop-reason
          (swap! state assoc :stop-reason (map-stop-reason stop-reason)))
        (when usage
          (swap! state update :usage merge (js->clj usage :keywordize-keys true))))

      nil)))

(defn- process-assistant-message
  "Fallback for runs without partial stream events."
  [^js message callbacks state]
  (when-not (:saw-stream-events @state)
    (let [^js msg (.-message message)
          content (when msg (js->clj (.-content msg) :keywordize-keys true))
          usage (when msg (js->clj (.-usage msg) :keywordize-keys true))]
      (when content
        (doseq [block content]
          (case (:type block)
            "text"     (when (:on-text callbacks) ((:on-text callbacks) (:text block)))
            "thinking" (when (:on-thinking callbacks) ((:on-thinking callbacks) (:thinking block)))
            "tool_use" (let [id (:id block)
                             tool-name (util/strip-mcp-prefix (:name block))
                             input (or (:input block) {})]
                         (swap! state update :tool-call-ids conj id)
                         (when (:on-tool-start callbacks)
                           ((:on-tool-start callbacks)
                            {:id id :name tool-name :arguments input})))
            nil)))
      (when usage
        (swap! state update :usage merge usage)))))

(defn- extract-tool-results-from-user-msg
  [^js message callbacks]
  (let [^js msg (.-message message)
        content (when msg (js->clj (.-content msg) :keywordize-keys true))]
    (when (and (sequential? content) (:on-tool-result callbacks))
      (doseq [block content]
        (when (= "tool_result" (:type block))
          ((:on-tool-result callbacks)
           {:id (:tool_use_id block)
            :content (:content block)
            :is-error (:is_error block)}))))))

(def ^:private fatal-api-errors
  "`api_retry` error kinds no amount of retrying fixes → what to tell the user.
   The CLI backs off through all its retries regardless, which leaves the turn
   sitting at \"thinking\" for minutes before the error finally surfaces."
  {"authentication_failed" "Claude authentication failed — the login has expired. Run `claude /login` on the host (or set ANTHROPIC_API_KEY)."
   "oauth_org_not_allowed" "Claude authentication failed — this organization is not allowed to use the OAuth login."
   "billing_error"         "Claude billing error — check the account's plan or credit balance."})

(defn process-sdk-message
  "Dispatch a single SDK message onto callbacks + per-turn `state` — the
   runner transport feeds these over the wire as `message` frames.
   Handles stream_event / assistant / user / result / system /
   rate_limit_event; loop control (done detection, abort) stays with the
   caller — a fatal `api_retry` only reports the error and sets :fatal-error,
   ending the turn is the caller's job."
  [^js message callbacks state]
  (let [msg-type (.-type message)]
    (case msg-type
      "stream_event"
      (do (swap! state assoc :saw-stream-events true)
          (process-stream-event (.-event message) callbacks state))

      "assistant"
      (process-assistant-message message callbacks state)

      "user"
      (extract-tool-results-from-user-msg message callbacks)

      "result"
      (let [result-text (.-result message)
            cost (.-total_cost_usd message)
            ^js usage (.-usage message)]
        (swap! state assoc
               :result-text result-text
               :is-error (boolean (.-is_error message))
               :cost cost :done true)
        (when usage
          (swap! state update :usage merge
                 (js->clj usage :keywordize-keys true))))

      "system"
      (case (.-subtype message)
        "init"
        (when-let [sid (.-session_id message)]
          (swap! state assoc :session-id sid)
          (when-let [f (:on-session callbacks)]
            (f sid)))

        "api_retry"
        (when-let [msg (and (not (:fatal-error @state))
                            (fatal-api-errors (.-error message)))]
          (swap! state assoc :fatal-error true)
          (when-let [f (:on-error callbacks)]
            (f {:type "error" :message msg})))

        nil)

      "rate_limit_event"
      (let [^js info (.-rate_limit_info message)]
        ;; A "rejected" rate limit is only a hard stop when overage isn't
        ;; covering it. When overage is allowed / already in use the request
        ;; proceeds, so don't surface it as an error the user has to see.
        (when (and info (= "rejected" (.-status info))
                   (not (= "allowed" (.-overageStatus info)))
                   (not (.-isUsingOverage info))
                   (:on-error callbacks))
          ((:on-error callbacks)
           {:type "rate_limit"
            :info (js->clj info :keywordize-keys true)})))

      nil)))

;; ── Runner transport (out-of-process SDK) ─────────────────────────────────────
;; Spawn `packages/providers/anthropic/runner.mjs` (its own node_modules, freely
;; upgradable SDK) per turn. Spawning, framing and tool-call proxying live in
;; xi.providers.runner; this section supplies what is Claude-specific: the
;; query options, the prompt shape and the SDK message decoding.

(defn- initial-turn-state [opts]
  (atom {:content [] :usage {} :stop-reason nil
         :model (:model opts) :session-id nil
         :result-text nil :cost nil :tool-call-ids []
         :pending-tool-inputs {} :saw-stream-events false}))

(defn- runner-path []
  (or (aget js/process.env "XI_CLAUDE_RUNNER_PATH")
      (runner/script-path :anthropic)))

(defn- base-query-opts
  "JSON-serializable query options for the runner. Excludes mcpServers, env,
   and pathToClaudeCodeExecutable — the runner supplies those itself.

   :settingSources decides which instruction files the CLI loads on its own;
   omitted, it loads everything (user CLAUDE.md + project CLAUDE.md/AGENTS.md).
   - Main turns load only `user` (~/.claude/CLAUDE.md): project instructions
     are already in `append-sys` (xi.system-prompt), so letting the CLI load
     them too sends the same file twice.
   - Text-only side turns (:no-tools? — titles, quick replies, summaries)
     load nothing; they carry their whole instruction in the prompt."
  [opts append-sys]
  (cond-> {:cwd (or (:cwd opts) (.cwd js/process))
           :settingSources (if (:no-tools? opts) [] ["user"])
           :permissionMode "bypassPermissions"
           :allowDangerouslySkipPermissions true
           :includePartialMessages true
           :tools []
           :allowedTools (if (:no-tools? opts) [] [(str MCP_TOOL_PREFIX "*")])
           :strictMcpConfig true
           :autoMemoryEnabled false}
    (:model opts) (assoc :model (:model opts))
    append-sys (assoc :systemPrompt {:type "preset" :preset "claude_code"
                                     :append append-sys})
    (:effort opts) (assoc :effort (:effort opts))
    (:resume-session-id opts) (assoc :resume (:resume-session-id opts))))

(defn- runner-prompt
  "Build the runner's `prompt` value: a plain string, or {:text :blocks} when
   there are inline image/PDF attachments (reconstructed into API content
   blocks by the runner)."
  [opts]
  (let [inline (filter (fn [{:keys [media-type]}]
                         (or (= media-type "application/pdf")
                             (str/starts-with? (or media-type "") "image/")))
                       (:images opts))]
    (if (seq inline)
      {:text (:prompt opts)
       :blocks (mapv (fn [{:keys [media-type data]}]
                       {:media_type media-type :data data})
                     inline)}
      (:prompt opts))))

(defn stream-messages-runner
  "Run one Claude turn through the SDK runner (xi.providers.runner): forward
   its SDK messages through process-sdk-message and service proxied tool calls
   on the host. Returns {:promise :abort!}."
  [opts]
  (let [callbacks (select-keys opts [:on-text :on-thinking :on-tool-start
                                     :on-tool-args :on-tool-result :on-session
                                     :on-error])
        state (initial-turn-state opts)
        !abort (volatile! nil)
        cwd (or (:cwd opts) (.cwd js/process))
        resume-id (:resume-session-id opts)
        append-sys (if (:no-tools? opts)
                     (:system opts)
                     (str/join "\n\n" (remove str/blank? [(:system opts) TOOL_NAMING_NOTE])))
        ;; the runner transport ships `defs` over the wire and dispatches
        ;; proxied calls via `registry`
        {:keys [defs registry]} (tools/resolve-tooling opts)
        {:keys [promise abort!]}
        (runner/run-turn!
         {:script (runner-path)
          :log-tag "claude-runner"
          :start {:queryOpts (base-query-opts opts append-sys)
                  :envOverride (:env opts)
                  :toolDefs (when-not (:no-tools? opts) defs)
                  :prompt (runner-prompt opts)
                  :noTools (boolean (:no-tools? opts))}
          :on-message (fn [message]
                        (let [fatal-before? (:fatal-error @state)]
                          (process-sdk-message message callbacks state)
                          ;; Already reported — stop the CLI's pointless retries.
                          (when (and (:fatal-error @state) (not fatal-before?))
                            (when-let [f @!abort] (f)))))
          :on-tool-call (tool-dispatcher {:registry registry
                                          :tool-policy (:tool-policy opts)
                                          :tool-ctx (:tool-ctx opts)
                                          :cwd cwd
                                          :client-pid (:client-pid opts)})
          :on-error (fn [msg]
                      (cond
                        ;; The abort above; the real error is already out.
                        (:fatal-error @state) nil

                        (and resume-id (re-find #"No conversation found" msg))
                        (swap! state assoc :resume-failed true)

                        (cwd-missing? cwd)
                        (swap! state assoc :cwd-missing true)

                        :else
                        (do
                          (js/console.error "[claude-runner] error:" msg)
                          (when (:on-error callbacks)
                            ((:on-error callbacks) {:type "error" :message msg})))))})]
    (vreset! !abort abort!)
    {:promise (.then promise (fn [_] @state))
     :abort! (fn []
               (swap! state assoc :aborted true)
               (abort!))}))

(def model-ids
  "Anthropic model ids offered in the model picker (static — the subscription
   backend has no public listing endpoint)."
  ["claude-fable-5-1" "claude-opus-5-5" "claude-sonnet-5-5"
   "claude-opus-5" "claude-opus-4-8" "claude-fable-5"
   "claude-sonnet-5" "claude-opus-4-6" "claude-sonnet-4-6"
   "claude-haiku-4-5-20251001"])

(defn list-models!
  "Promise of the static Anthropic model-id vector."
  []
  (js/Promise.resolve model-ids))

(def provider
  {:id :anthropic
   :start-turn! stream-messages-runner
   :list-models! list-models!})
