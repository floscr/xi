(ns xi.providers.anthropic
  "Claude provider — uses the Claude Agent SDK for API access.
   Claude proposes tool calls via MCP; Xi intercepts and executes them
   through its own tool pipeline. Mirrors Pi's claude-bridge architecture.

   SDK lore (do not lose):
   - SDK pinned to 0.2.110 — newer versions exit 127 (CLI resolution breaks)
   - The query MUST be closed via .close() after completion to avoid EPIPE
     from orphaned subprocess pipes; error paths must close too
   - Abort = .interrupt() (graceful) then .close() (cleanup), never .return()

   Interface: (stream-messages opts) → {:promise :abort!}.
   Extension hooks are injected via :tool-gate (async transform; nil blocks,
   {:intercepted true :result …} short-circuits) — no ext/core dependency."
  (:require ["@anthropic-ai/claude-agent-sdk" :as sdk]
            ["node:child_process" :as child-process]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["zod" :as z]
            [clojure.string :as str]
            [xi.tools.registry :as tools]
            [xi.util :as util]))

;; ── Claude Code Executable Resolution ─────────────────────────────────────────

(defn resolve-claude-executable
  "Resolve the claude CLI executable path. Returns path string or nil."
  []
  (try
    (let [which-path (-> (child-process/execSync "which claude" #js {:encoding "utf8"})
                         (.trim))
          real-path (fs/realpathSync which-path)]
      (when (.endsWith real-path ".js")
        real-path))
    (catch :default _e nil)))

(def ^:private claude-executable
  (delay (resolve-claude-executable)))

(defn- cwd-missing?
  "True when a working directory is set but doesn't exist on this host. A Pi
   session (cwd=/var/lib/xi) opened on a machine without that dir makes Node's
   spawn throw ENOENT, which the SDK mislabels as 'executable not found';
   detecting the missing dir lets the agent recover instead."
  [cwd]
  (and (string? cwd)
       (pos? (count cwd))
       (try (not (.existsSync fs cwd)) (catch :default _ false))))

;; ── JSON Schema → Zod ─────────────────────────────────────────────────────────
;;
;; createSdkMcpServer needs Zod schemas. Convert our JSON Schema tool defs.

(defn- parse-json-string
  "If v is a string, try to JSON.parse it; on failure return the original
   string (so validation reports a clear type error). Non-strings pass
   through untouched. Lets models that serialize structured args as a JSON
   string still satisfy array/object schemas."
  [v]
  (if (string? v)
    (try (js/JSON.parse v) (catch :default _ v))
    v))

(defn- json-schema-prop->zod
  [prop]
  (let [prop-type (get prop :type)
        enum-vals (get prop :enum)
        base (cond
               (seq enum-vals) (.enum z (clj->js enum-vals))
               (= "string" prop-type) (.string z)
               (or (= "number" prop-type) (= "integer" prop-type)) (.number z)
               (= "boolean" prop-type) (.boolean z)
               (= "array" prop-type) (.preprocess z parse-json-string
                                       (if-let [items (get prop :items)]
                                         (.array z (json-schema-prop->zod items))
                                         (.array z (.unknown z))))
               (= "object" prop-type) (.preprocess z parse-json-string
                                        (if-let [props (get prop :properties)]
                                          (let [required-set (set (get prop :required))
                                                shape (reduce-kv
                                                       (fn [acc k v]
                                                         (let [zod-prop (json-schema-prop->zod v)]
                                                           (assoc acc k (if (contains? required-set k)
                                                                          zod-prop
                                                                          (.optional zod-prop)))))
                                                       {} props)]
                                            (.object z (clj->js shape)))
                                          (.record z (.string z) (.unknown z))))
               :else (.unknown z))]
    (if-let [desc (get prop :description)]
      (.describe base desc)
      base)))

(defn- json-schema->zod-shape
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
;; Claude's built-in tools are disabled (tools: [] whitelist) — Xi exposes
;; its own via MCP. Each call passes through :tool-gate before execution.

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

(def ^:private PERSONAL_AGENT_TOOLS
  "Tools available in personal-agent mode."
  #{"web_search" "amazon_search" "willhaben_search" "geizhals_search"})

(def ^:private default-gate
  "Pass-through tool gate (extensions inject the real one)."
  (fn [tool-call] (js/Promise.resolve tool-call)))

(defn run-gated-tool
  "Execute one tool call through the extension tool-gate, then the registry.
   Returns a Promise of #js {:content … :isError …}. Shared by the in-process
   MCP bridge and (slice 2) the runner's proxied tool-call handler, so the
   permission gate + registry stay in one place regardless of transport.

   opts: {:tool-name :exec-fn :arguments :tool-gate :cwd :client-pid}."
  [{:keys [tool-name exec-fn arguments tool-gate cwd client-pid]}]
  (let [tool-gate (or tool-gate default-gate)
        tool-call {:name tool-name :arguments arguments}]
    (-> (tool-gate tool-call)
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
                                 {:cwd cwd :client-pid client-pid})
                 (.then (fn [{:keys [content is-error]}]
                          #js {:content (clj->js content)
                               :isError is-error})))))))))

(defn resolve-tooling
  "Resolve the enabled tool defs + registry for a turn: extension extras,
   removals, and the personal-agent `only-tools` filter applied. `:defs` is the
   ordered tool-definition vector shown to the model; `:registry` maps
   tool-name → exec-fn. Shared by the in-process MCP bridge and the runner
   transport (which ships `:defs` over the wire and dispatches via `:registry`).

   extra-tool-definitions / extra-tool-registry may be a value OR a 0-arg fn.
   The manager passes fns (xi.cli/tooling-opts) so the enabled tool set is read
   *fresh each turn* — enabling/disabling an extension changes what the model
   sees on the next turn without a restart (see xi.ext.manager)."
  [{:keys [only-tools extra-tool-definitions extra-tool-registry remove-tools]}]
  (let [extra-defs (if (fn? extra-tool-definitions)
                     (extra-tool-definitions) extra-tool-definitions)
        extra-registry (if (fn? extra-tool-registry)
                         (extra-tool-registry) extra-tool-registry)
        removed (if (fn? remove-tools) (remove-tools) remove-tools)
        all-defs (into (tools/tool-definitions) extra-defs)
        all-defs (if (seq removed)
                   (filterv #(not (contains? removed (:name %))) all-defs)
                   all-defs)
        defs (if only-tools
               (filterv #(contains? only-tools (:name %)) all-defs)
               all-defs)
        registry (merge (tools/tool-registry) extra-registry)]
    {:defs defs :registry registry}))

(defn tool-dispatcher
  "Return a fn `(tool-name clj-args) → Promise<#js {:content :isError}>` that
   runs a tool through the gate + registry. Used by the runner transport to
   service proxied `tool-call` frames on the host."
  [{:keys [registry tool-gate cwd client-pid]}]
  (fn [tool-name arguments]
    (run-gated-tool {:tool-name tool-name
                     :exec-fn (get registry tool-name)
                     :arguments arguments
                     :tool-gate tool-gate
                     :cwd cwd
                     :client-pid client-pid})))

(defn- build-mcp-server
  [{:keys [cwd tool-gate client-pid] :as opts}]
  (let [tool-gate (or tool-gate default-gate)
        {:keys [defs registry]} (resolve-tooling opts)
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
                                   (run-gated-tool {:tool-name tool-name
                                                    :exec-fn exec-fn
                                                    :arguments (js->clj args :keywordize-keys true)
                                                    :tool-gate tool-gate
                                                    :cwd cwd
                                                    :client-pid client-pid}))}))
                        defs))]
    (sdk/createSdkMcpServer
     #js {:name MCP_SERVER_NAME
          :version "1.0.0"
          :tools mcp-tools})))

;; ── Stream Event Processing ──────────────────────────────────────────────────

(defn map-stop-reason [reason]
  (case reason
    "tool_use"   "toolUse"
    "max_tokens" "length"
    "end_turn"   "stop"
    "stop"))

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
          (swap! state update-in [:pending-tool-inputs idx :json-chunks] conj (.-partial_json delta))
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

(defn process-sdk-message
  "Dispatch a single SDK message onto callbacks + per-turn `state`. Shared by
   the in-process iterator loop and (slice 2) the runner transport, which feeds
   the same messages over the wire. Handles stream_event / assistant / user /
   result / system / rate_limit_event; loop control (done detection, recursion,
   abort) stays with the caller."
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
      (when (= "init" (.-subtype message))
        (when-let [sid (.-session_id message)]
          (swap! state assoc :session-id sid)
          (when-let [f (:on-session callbacks)]
            (f sid))))

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

;; ── Main Streaming Function ──────────────────────────────────────────────────

(defn stream-messages
  "Send a prompt to Claude via the SDK with the MCP tool bridge.
   Returns {:promise p :abort! f} — promise resolves to the response state
   map; abort! interrupts gracefully then closes (state gets :aborted)."
  [opts]
  (let [callbacks (select-keys opts [:on-text :on-thinking :on-tool-start
                                     :on-tool-args :on-tool-result :on-session
                                     :on-error])
        ;; Per-turn stream accumulation — contained to this turn.
        state (atom {:content [] :usage {} :stop-reason nil
                     :model (:model opts) :session-id nil
                     :result-text nil :cost nil :tool-call-ids []
                     :pending-tool-inputs {} :saw-stream-events false})

        cwd (or (:cwd opts) (.cwd js/process))
        resume-id (:resume-session-id opts)
        ;; A text-only turn (:no-tools?, e.g. title generation) skips the MCP
        ;; bridge entirely: it needs no tools, and building/attaching a second
        ;; xi-tools server concurrently with the first user turn made that
        ;; turn's first tool calls fail with "No such tool available" until the
        ;; extra CLI subprocess's MCP handshake finished (see docs).
        mcp-server (when-not (:no-tools? opts)
                     (build-mcp-server
                      (cond-> {:cwd cwd
                               :client-pid (:client-pid opts)
                               :tool-gate (:tool-gate opts)
                               :extra-tool-definitions (:extra-tool-definitions opts)
                               :extra-tool-registry (:extra-tool-registry opts)
                               :remove-tools (:remove-tools opts)}
                        (:personal-agent? opts) (assoc :only-tools PERSONAL_AGENT_TOOLS))))
        ;; Append the tool-naming note on tool turns so the model calls the
        ;; mcp__xi-tools__* names, not the disabled native builtins. Skipped
        ;; for :no-tools? turns (title gen etc.) which expose no tools.
        append-sys (if (:no-tools? opts)
                     (:system opts)
                     (str/join "\n\n" (remove str/blank? [(:system opts) TOOL_NAMING_NOTE])))
        query-opts (let [base (clj->js
                               (cond-> {:cwd cwd
                                        :permissionMode "bypassPermissions"
                                        :allowDangerouslySkipPermissions true
                                        :includePartialMessages true
                                        ;; Whitelist approach: disable ALL builtins
                                        :tools []
                                        :allowedTools (if (:no-tools? opts)
                                                        []
                                                        [(str MCP_TOOL_PREFIX "*")])
                                        ;; Ignore filesystem MCP config
                                        ;; (~/.claude.json). Without this the
                                        ;; SDK merges the user's native MCP
                                        ;; servers with ours and spawns them; we
                                        ;; want ONLY our programmatic xi-tools
                                        ;; server so the inner agent can't reach
                                        ;; native MCP (e.g. chrome-devtools).
                                        :strictMcpConfig true
                                        ;; Disable the SDK's auto-memory feature
                                        ;; (~/.claude/projects/<cwd>/memory/,
                                        ;; enabled by default with the
                                        ;; claude_code preset). We don't want the
                                        ;; inner agent reading or writing memory.
                                        :autoMemoryEnabled false}
                                 @claude-executable
                                 (assoc :pathToClaudeCodeExecutable @claude-executable)

                                 (:model opts)
                                 (assoc :model (:model opts))

                                 append-sys
                                 (assoc :systemPrompt
                                        #js {:type "preset"
                                             :preset "claude_code"
                                             :append append-sys})

                                 (:effort opts)
                                 (assoc :effort (:effort opts))

                                 ;; Per-turn env override, merged over
                                 ;; process.env (the SDK replaces env wholesale,
                                 ;; so we must keep PATH/auth/etc).
                                 (:env opts)
                                 (assoc :env (merge (js->clj js/process.env)
                                                    (:env opts)))

                                 resume-id
                                 (assoc :resume resume-id)))]
                     (when mcp-server
                       (unchecked-set base "mcpServers"
                                      (js-obj MCP_SERVER_NAME mcp-server)))
                     base)

        images (:images opts)
        ;; Only images and PDFs can be inlined as API content blocks (vision /
        ;; document). Any other attachment (zip, text, …) reaches the model only
        ;; via its on-disk path, already appended to the prompt text upstream.
        inline (filter (fn [{:keys [media-type]}]
                         (or (= media-type "application/pdf")
                             (str/starts-with? (or media-type "") "image/")))
                       images)
        ;; With inline attachments: SDKUserMessage with multipart content via
        ;; async generator (same pattern as pi's claude-bridge); else plain
        ;; string.
        prompt-value
        (if (seq inline)
          (let [text-blocks (when (seq (:prompt opts))
                              [#js {:type "text" :text (:prompt opts)}])
                content (into-array
                         (concat
                          text-blocks
                          (map (fn [{:keys [media-type data]}]
                                 (if (= media-type "application/pdf")
                                   #js {:type "document"
                                        :source #js {:type "base64"
                                                     :media_type media-type
                                                     :data data}}
                                   #js {:type "image"
                                        :source #js {:type "base64"
                                                     :media_type media-type
                                                     :data data}}))
                               inline)))
                msg #js {:type "user"
                         :message #js {:role "user" :content content}
                         :parent_tool_use_id nil}]
            ((js* "(async function*(msg) { yield msg; })") msg))
          (:prompt opts))

        ^js sdk-query (sdk/query #js {:prompt prompt-value
                                      :options query-opts})
        flags #js {:aborted false}
        close-query! (fn []
                       (try (.close sdk-query)
                            (catch :default _e nil)))

        promise
        (js/Promise.
         (fn [resolve _reject]
           (letfn [(finish! []
                     (close-query!)
                     (resolve @state))

                   (consume []
                     (if (.-aborted flags)
                       (do
                         (swap! state assoc :aborted true)
                         (finish!))
                       (-> (.next sdk-query)
                           (.then
                            (fn [^js result]
                              (if (.-done result)
                                (finish!)
                                (do
                                  (process-sdk-message (.-value result) callbacks state)
                                  (consume)))))
                           (.catch
                            (fn [err]
                              (let [msg (str (.-message err))]
                                (cond
                                  (and resume-id
                                       (re-find #"No conversation found" msg))
                                  ;; The session we tried to resume is gone from
                                  ;; disk. Signal the agent to retry fresh rather
                                  ;; than surfacing a dead-end error to the user.
                                  (do (swap! state assoc :resume-failed true)
                                      (finish!))

                                  ;; The working directory vanished (e.g. a Pi
                                  ;; session with cwd=/var/lib/xi opened on a host
                                  ;; without it). spawn ENOENT is mislabeled
                                  ;; 'executable not found'; recover via the agent
                                  ;; (cwd-select dialog) rather than dead-ending.
                                  (cwd-missing? cwd)
                                  (do (swap! state assoc :cwd-missing true)
                                      (finish!))

                                  :else
                                  (do
                                    (js/console.error "[claude] stream error:" msg)
                                    (when (:on-error callbacks)
                                      ((:on-error callbacks)
                                       {:type "error" :message msg}))
                                    (finish!)))))))))]
             (consume))))]

    {:promise promise
     :abort!  (fn []
                (set! (.-aborted flags) true)
                (swap! state assoc :aborted true)
                ;; interrupt() asks the CLI to stop gracefully; close() kills it
                (-> (.interrupt sdk-query)
                    (.catch (fn [_] nil)))
                (close-query!))}))

;; ── Runner transport (out-of-process SDK) ─────────────────────────────────────
;; Opt-in via XI_CLAUDE_RUNNER=1: instead of running the Claude Agent SDK
;; in-process, spawn `runner/runner.mjs` (its own node_modules, freely
;; upgradable SDK) per turn and speak newline-delimited JSON over stdio. The
;; host still owns the permission gate + tool registry — the runner proxies
;; each tool call back via `tool-call` frames (see runner/runner.mjs).

(defn- initial-turn-state [opts]
  (atom {:content [] :usage {} :stop-reason nil
         :model (:model opts) :session-id nil
         :result-text nil :cost nil :tool-call-ids []
         :pending-tool-inputs {} :saw-stream-events false}))

(defn- runner-path []
  (or (aget js/process.env "XI_CLAUDE_RUNNER_PATH")
      (.resolve path js/__dirname ".." "runner" "runner.mjs")))

(defn- base-query-opts
  "JSON-serializable query options for the runner. Excludes mcpServers, env,
   and pathToClaudeCodeExecutable — the runner supplies those itself."
  [opts append-sys]
  (cond-> {:cwd (or (:cwd opts) (.cwd js/process))
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
  "Runner-transport variant of stream-messages: spawn runner/runner.mjs per
   turn, forward SDK messages through process-sdk-message, and service proxied
   tool-call frames on the host. Same {:promise :abort!} contract."
  [opts]
  (let [callbacks (select-keys opts [:on-text :on-thinking :on-tool-start
                                     :on-tool-args :on-tool-result :on-session
                                     :on-error])
        state (initial-turn-state opts)
        cwd (or (:cwd opts) (.cwd js/process))
        resume-id (:resume-session-id opts)
        append-sys (if (:no-tools? opts)
                     (:system opts)
                     (str/join "\n\n" (remove str/blank? [(:system opts) TOOL_NAMING_NOTE])))
        {:keys [defs registry]} (resolve-tooling
                                 (cond-> {:extra-tool-definitions (:extra-tool-definitions opts)
                                          :extra-tool-registry (:extra-tool-registry opts)
                                          :remove-tools (:remove-tools opts)}
                                   (:personal-agent? opts) (assoc :only-tools PERSONAL_AGENT_TOOLS)))
        dispatch (tool-dispatcher {:registry registry
                                   :tool-gate (:tool-gate opts)
                                   :cwd cwd
                                   :client-pid (:client-pid opts)})
        query-opts (base-query-opts opts append-sys)
        ^js proc (child-process/spawn (.-execPath js/process)
                                      #js [(runner-path)]
                                      #js {:stdio #js ["pipe" "pipe" "inherit"]})
        send-frame! (fn [m]
                      (try
                        (.write (.-stdin proc) (str (js/JSON.stringify (clj->js m)) "\n"))
                        (catch :default _e nil)))
        promise
        (js/Promise.
         (fn [resolve _reject]
           (let [buf (atom "")
                 done? (atom false)
                 finish! (fn []
                           (when-not @done?
                             (reset! done? true)
                             (try (.kill proc) (catch :default _e nil))
                             (resolve @state)))
                 handle-frame
                 (fn [^js frame]
                   ;; Frames after the terminal `done`/`error` (e.g. a late
                   ;; runner error while we're killing it) must not fire
                   ;; callbacks into an already-resolved turn.
                   (when-not @done?
                    (case (aget frame "type")
                     "sdk-message"
                     (process-sdk-message (aget frame "message") callbacks state)

                     "tool-call"
                     (let [id (aget frame "id")
                           tool-name (aget frame "name")
                           args (js->clj (aget frame "arguments") :keywordize-keys true)]
                       (-> (dispatch tool-name args)
                           (.then (fn [^js res]
                                    (send-frame! {:type "tool-result"
                                                  :id id
                                                  :result {:content (.-content res)
                                                           :isError (.-isError res)}})))
                           (.catch (fn [e]
                                     (send-frame!
                                      {:type "tool-result" :id id
                                       :result {:content #js [#js {:type "text" :text (str e)}]
                                                :isError true}})))))

                     "done" (finish!)

                     "error"
                     (let [msg (str (aget frame "message"))]
                       (cond
                         (and resume-id (re-find #"No conversation found" msg))
                         (do (swap! state assoc :resume-failed true) (finish!))

                         (cwd-missing? cwd)
                         (do (swap! state assoc :cwd-missing true) (finish!))

                         :else
                         (do
                           (js/console.error "[claude-runner] error:" msg)
                           (when (:on-error callbacks)
                             ((:on-error callbacks) {:type "error" :message msg}))
                           (finish!))))

                     nil)))]
             (.setEncoding (.-stdout proc) "utf8")
             (.on (.-stdout proc) "data"
                  (fn [chunk]
                    (swap! buf str chunk)
                    (loop []
                      (let [s @buf
                            nl (.indexOf s "\n")]
                        (when (>= nl 0)
                          (let [line (subs s 0 nl)]
                            (reset! buf (subs s (inc nl)))
                            (when-not (str/blank? line)
                              (try (handle-frame (js/JSON.parse line))
                                   (catch :default e
                                     (js/console.error "[claude-runner] bad frame:" (str e)))))
                            (recur)))))))
             (.on proc "error"
                  (fn [err]
                    (js/console.error "[claude-runner] spawn error:" (str err))
                    (when (:on-error callbacks)
                      ((:on-error callbacks) {:type "error" :message (str err)}))
                    (finish!)))
             (.on proc "close" (fn [_code] (finish!)))
             (send-frame! {:type "start"
                           :queryOpts query-opts
                           :envOverride (:env opts)
                           :toolDefs (when-not (:no-tools? opts) defs)
                           :prompt (runner-prompt opts)
                           :noTools (boolean (:no-tools? opts))}))))]
    {:promise promise
     :abort! (fn []
               (swap! state assoc :aborted true)
               (send-frame! {:type "abort"})
               (js/setTimeout (fn [] (try (.kill proc) (catch :default _e nil))) 500))}))

(defn- start-turn!
  "Route a turn to the runner transport (opt-in via XI_CLAUDE_RUNNER=1) or the
   in-process SDK. Default stays in-process."
  [opts]
  (if (= "1" (aget js/process.env "XI_CLAUDE_RUNNER"))
    (stream-messages-runner opts)
    (stream-messages opts)))

(def model-ids
  "Anthropic model ids offered in the model picker (static — the subscription
   backend has no public listing endpoint)."
  ["claude-fable-5-1" "claude-opus-5" "claude-opus-4-8" "claude-fable-5"
   "claude-sonnet-5" "claude-opus-4-6" "claude-sonnet-4-6"
   "claude-haiku-4-5-20251001"])

(defn list-models!
  "Promise of the static Anthropic model-id vector."
  []
  (js/Promise.resolve model-ids))

(def provider
  {:id :anthropic
   :start-turn! start-turn!
   :list-models! list-models!})