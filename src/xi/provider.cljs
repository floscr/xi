(ns xi.provider
  "Provider that uses the Claude Agent SDK for API access.
   CC proposes tool calls via MCP; Xi intercepts and executes them
   through its own tool pipeline (with permission gate hooks).
   This mirrors Pi's claude-bridge architecture."
  (:require ["@anthropic-ai/claude-agent-sdk" :as sdk]
            ["node:child_process" :as child-process]
            ["node:fs" :as fs]
            ["zod" :as z]
            [xi.ext.core :as ext]
            [xi.tools.registry :as tools]))

;; ── Claude Code Executable Resolution ─────────────────────────────────────────

(defn- resolve-claude-executable []
  (try
    (let [which-path (-> (child-process/execSync "which claude" #js {:encoding "utf8"})
                        (.trim))
          real-path (fs/realpathSync which-path)]
      (when (.endsWith real-path ".js")
        real-path))
    (catch :default _e nil)))

(defonce ^:private claude-executable (resolve-claude-executable))

;; ── JSON Schema → Zod ─────────────────────────────────────────────────────────
;;
;; createSdkMcpServer needs Zod schemas. Convert our JSON Schema tool defs.

(defn- json-schema-prop->zod
  "Convert a single JSON Schema property to a Zod type."
  [prop]
  (let [prop-type (get prop :type)
        enum-vals (get prop :enum)
        base (cond
               (seq enum-vals) (.enum z (clj->js enum-vals))
               (= "string" prop-type) (.string z)
               (or (= "number" prop-type) (= "integer" prop-type)) (.number z)
               (= "boolean" prop-type) (.boolean z)
               (= "array" prop-type) (if-let [items (get prop :items)]
                                       (.array z (json-schema-prop->zod items))
                                       (.array z (.unknown z)))
               (= "object" prop-type) (if-let [props (get prop :properties)]
                                        ;; Nested object with properties — convert recursively
                                        (let [required-set (set (get prop :required))
                                              shape (reduce-kv
                                                     (fn [acc k v]
                                                       (let [zod-prop (json-schema-prop->zod v)]
                                                         (assoc acc k (if (contains? required-set k)
                                                                        zod-prop
                                                                        (.optional zod-prop)))))
                                                     {} props)]
                                          (.object z (clj->js shape)))
                                        ;; Generic object
                                        (.record z (.string z) (.unknown z)))
               :else (.unknown z))
        described (if-let [desc (get prop :description)]
                   (.describe base desc)
                   base)]
    described))

(defn- json-schema->zod-shape
  "Convert a JSON Schema object to a Zod shape (map of key → ZodType)."
  [schema]
  (let [props (get schema :properties)
        required-set (set (get schema :required))]
    (when props
      (reduce-kv
       (fn [acc k v]
         (let [zod-prop (json-schema-prop->zod v)]
           (unchecked-set acc (name k)
                          (if (contains? required-set (name k))
                            zod-prop
                            (.optional zod-prop)))
           acc))
       #js {} props))))

;; ── MCP Tool Bridge ───────────────────────────────────────────────────────────
;;
;; CC's built-in tools are disabled. Xi's tools are exposed via MCP.
;; When CC calls a tool:
;;   1. MCP handler dispatches :tool-call hook (permission gate can block)
;;   2. If allowed, executes via Xi's tool registry
;;   3. Returns result to CC

(def ^:private MCP_SERVER_NAME "xi-tools")

(def ^:private DISALLOWED_BUILTIN_TOOLS
  ["Read" "Write" "Edit" "Glob" "Grep" "Bash" "Agent" "AskClaude"
   "NotebookEdit" "EnterWorktree" "ExitWorktree"
   "CronCreate" "CronDelete" "CronList" "TeamCreate" "TeamDelete"
   "WebFetch" "WebSearch" "TodoRead" "TodoWrite"
   "EnterPlanMode" "ExitPlanMode" "RemoteTrigger" "SendMessage"
   "Skill" "TaskOutput" "TaskStop" "ToolSearch"
   "AskUserQuestion" "TaskCreate" "TaskGet" "TaskList" "TaskUpdate"])

(def ^:private MCP_TOOL_PREFIX (str "mcp__" MCP_SERVER_NAME "__"))

(defn- build-mcp-server
  "Build an MCP server exposing Xi's tools."
  [cwd]
  (let [defs (tools/tool-definitions)
        registry (tools/tool-registry)
        mcp-tools (into-array
                   (map (fn [tool-def]
                          (let [tool-name (:name tool-def)
                                exec-fn (get registry tool-name)
                                zod-shape (json-schema->zod-shape (:input_schema tool-def))]
                            #js {:name tool-name
                                 :description (:description tool-def)
                                 :inputSchema (or zod-shape #js {})
                                 :handler
                                 (fn [^js args _extra]
                                   (let [args (js->clj args :keywordize-keys true)
                                         tool-call {:name tool-name :arguments args}
                                         gated (ext/dispatch-hook-transform
                                                :tool-call tool-call {:cwd cwd})]
                                     (if (nil? gated)
                                       ;; Blocked by permission gate
                                       (js/Promise.resolve
                                        #js {:content #js [#js {:type "text"
                                                                :text "Blocked by Xi permission gate"}]
                                             :isError true})
                                       ;; Execute the tool
                                       (-> (let [result (exec-fn args {:cwd cwd})]
                                             (if (instance? js/Promise result)
                                               result
                                               (js/Promise.resolve result)))
                                           (.then (fn [result]
                                                    #js {:content (clj->js (:content result))
                                                         :isError (boolean (:is-error result))}))
                                           (.catch (fn [err]
                                                     #js {:content #js [#js {:type "text"
                                                                            :text (str "Tool error: " (.-message err))}]
                                                          :isError true}))))))}))
                        defs))]
    (sdk/createSdkMcpServer
     #js {:name MCP_SERVER_NAME
          :version "1.0.0"
          :tools mcp-tools})))

;; ── Session State ─────────────────────────────────────────────────────────────

(defonce ^:private session-state (atom nil))

(defn get-session-id []
  (:session-id @session-state))

(defn clear-session! []
  (reset! session-state nil))

;; ── Stream Event Processing ──────────────────────────────────────────────────

(defn- map-stop-reason [reason]
  (case reason
    "tool_use"   "toolUse"
    "max_tokens" "length"
    "end_turn"   "stop"
    "stop"))

(defn- strip-mcp-prefix
  "Strip MCP prefix: mcp__xi-tools__bash → bash"
  [n]
  (if (and n (.startsWith n MCP_TOOL_PREFIX))
    (subs n (count MCP_TOOL_PREFIX))
    n))

(defn- process-stream-event
  [^js event callbacks state]
  (let [event-type (.-type event)]
    (case event-type
      "message_start"
      (let [usage (some-> event .-message .-usage)]
        (when usage
          (swap! state assoc :usage (js->clj usage :keywordize-keys true))))

      "content_block_start"
      (let [^js block (.-content_block event)
            block-type (.-type block)
            idx (.-index event)]
        (case block-type
          "text"     nil
          "thinking" nil
          "tool_use" (let [id (.-id block)
                           tool-name (strip-mcp-prefix (.-name block))
                           input (or (js->clj (.-input block) :keywordize-keys true) {})]
                       (swap! state (fn [s]
                                      (-> s
                                          (update :tool-call-ids conj id)
                                          (assoc-in [:pending-tool-inputs idx]
                                                    {:id id :name tool-name :json-chunks []}))))
                       (when (:on-tool-start callbacks)
                         ((:on-tool-start callbacks)
                          {:id id :name tool-name :arguments input})))
          nil))

      "content_block_delta"
      (let [^js delta (.-delta event)
            delta-type (.-type delta)
            idx (.-index event)]
        (case delta-type
          "text_delta"     (when (:on-text callbacks) ((:on-text callbacks) (.-text delta)))
          "thinking_delta" (when (:on-thinking callbacks) ((:on-thinking callbacks) (.-thinking delta)))
          "input_json_delta"
          (swap! state update-in [:pending-tool-inputs idx :json-chunks] conj (.-partial_json delta))
          "signature_delta"  nil
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

      "message_stop" nil
      "ping" nil
      nil)))

(defn- process-assistant-message
  [^js message callbacks state saw-stream-events?]
  (when-not saw-stream-events?
    (let [^js msg (.-message message)
          content (when msg (js->clj (.-content msg) :keywordize-keys true))
          usage (when msg (js->clj (.-usage msg) :keywordize-keys true))]
      (when content
        (doseq [block content]
          (case (:type block)
            "text"     (when (:on-text callbacks) ((:on-text callbacks) (:text block)))
            "thinking" (when (:on-thinking callbacks) ((:on-thinking callbacks) (:thinking block)))
            "tool_use" (let [id (:id block)
                             tool-name (strip-mcp-prefix (:name block))
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
           {:name (:tool_use_id block)
            :content (:content block)
            :is-error (:is_error block)}))))))

;; ── Main Streaming Function ──────────────────────────────────────────────────

(defn stream-messages
  "Send a prompt to Claude via the SDK with MCP tool bridge.
   CC proposes tools, Xi executes them. Returns promise of response state."
  [opts]
  (let [callbacks (select-keys opts [:on-text :on-thinking :on-tool-start
                                     :on-tool-args :on-tool-result :on-error])
        state (atom {:content [] :usage {} :stop-reason nil
                     :model (:model opts) :session-id nil
                     :result-text nil :cost nil :tool-call-ids []
                     :pending-tool-inputs {}})
        saw-stream-events? (atom false)

        cwd (or (:cwd opts) (.cwd js/process))
        resume-id (or (:resume-session-id opts) (get-session-id))
        mcp-server (build-mcp-server cwd)
        query-opts (doto (clj->js
                          (cond-> {:cwd cwd
                                   :permissionMode "bypassPermissions"
                                   :allowDangerouslySkipPermissions true
                                   :includePartialMessages true
                                   :disallowedTools DISALLOWED_BUILTIN_TOOLS
                                   :allowedTools [(str MCP_TOOL_PREFIX "*")]}
                            claude-executable
                            (assoc :pathToClaudeCodeExecutable claude-executable)

                            (:model opts)
                            (assoc :model (:model opts))

                            (:system opts)
                            (assoc :systemPrompt
                                   #js {:type "preset"
                                        :preset "claude_code"
                                        :append (:system opts)})

                            resume-id
                            (assoc :resume resume-id)))
                     (unchecked-set "mcpServers"
                                    (js-obj MCP_SERVER_NAME mcp-server)))

        abort-signal (:abort-signal opts)
        ^js sdk-query (sdk/query #js {:prompt (:prompt opts)
                                      :options query-opts})]

    (js/Promise.
     (fn [resolve _reject]
       (let [consume
             (fn consume []
               ;; Check abort signal before each iteration
               (if (and abort-signal @abort-signal)
                 (do
                   ;; Signal the async iterator to stop
                   (when (.-return sdk-query)
                     (.return sdk-query))
                   (swap! state assoc :aborted true)
                   (resolve @state))
                 (-> (.next sdk-query)
                     (.then
                      (fn [^js result]
                        (if (.-done result)
                          (resolve @state)
                        (let [^js message (.-value result)
                              msg-type (.-type message)]
                          (case msg-type
                            "stream_event"
                            (do (reset! saw-stream-events? true)
                                (process-stream-event
                                 (.-event message) callbacks state))

                            "assistant"
                            (process-assistant-message
                             message callbacks state @saw-stream-events?)

                            "user"
                            (extract-tool-results-from-user-msg message callbacks)

                            "result"
                            (let [result-text (.-result message)
                                  cost (.-total_cost_usd message)
                                  ^js usage (.-usage message)]
                              (swap! state assoc
                                     :result-text result-text
                                     :cost cost :done true)
                              (when usage
                                (swap! state update :usage merge
                                       (js->clj usage :keywordize-keys true))))

                            "system"
                            (let [subtype (.-subtype message)]
                              (when (= "init" subtype)
                                (let [sid (.-session_id message)]
                                  (when sid
                                    (swap! state assoc :session-id sid)
                                    (reset! session-state
                                            {:session-id sid :cursor 0 :cwd cwd})))))

                            "rate_limit_event"
                            (let [^js info (.-rate_limit_info message)]
                              (when (and info (= "rejected" (.-status info))
                                         (:on-error callbacks))
                                ((:on-error callbacks)
                                 {:type "rate_limit"
                                  :info (js->clj info :keywordize-keys true)})))

                            nil)
                          (consume)))))
                   (.catch
                    (fn [err]
                      (when (:on-error callbacks)
                        ((:on-error callbacks)
                         {:type "error" :message (.-message err)}))
                      (resolve @state))))))]
         (consume))))))

(defn response->assistant-message [state]
  {:role "assistant"
   :content (or (:content state) [])
   :model (:model state)
   :usage (:usage state)
   :stop-reason (:stop-reason state)})