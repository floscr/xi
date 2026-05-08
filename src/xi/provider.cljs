(ns xi.provider
  "Provider that uses Claude CLI as a bridge for API access.
   Spawns `claude -p` subprocess which handles auth, rate limits, caching.
   Xi's custom tools are exposed to the CLI via MCP over stdio."
  (:require [clojure.string :as str]))


;; ── Claude CLI Bridge ─────────────────────────────────────────────────────────
;;
;; Architecture:
;;   Xi spawns `claude -p --output-format stream-json` for each agent turn.
;;   The CLI runs its own agent loop with built-in tools (Read, Write, Edit, Bash, etc.)
;;   Xi's custom extension tools are exposed via --mcp-config as an MCP server.
;;   The CLI handles auth, rate limits, prompt caching, and tool execution.
;;   Xi receives the full stream of events and displays them.

(defn- build-cli-args
  "Build claude CLI args for a streaming query."
  [{:keys [model system mcp-config resume-session-id]}]
  (cond-> ["claude" "-p"
           "--output-format" "stream-json"
           "--verbose"
           "--dangerously-skip-permissions"]
    model              (into ["--model" model])
    system             (into ["--append-system-prompt" system])
    mcp-config         (into ["--mcp-config" mcp-config "--strict-mcp-config"])
    resume-session-id  (into ["-r" resume-session-id])))

(defn- parse-json-line
  "Parse a JSON line. Returns nil on parse failure."
  [line]
  (when (and (string? line) (seq line))
    (try
      (js->clj (js/JSON.parse line) :keywordize-keys true)
      (catch :default _e nil))))

(defn- extract-content-blocks
  "Extract content blocks from an assistant message, converting to Xi internal format."
  [assistant-msg]
  (let [content (get-in assistant-msg [:message :content])]
    (when (seq content)
      (mapv (fn [block]
              (case (:type block)
                "text"     {:type "text" :text (:text block)}
                "thinking" {:type "thinking"
                            :thinking (or (:thinking block) "")
                            :thinkingSignature (or (:signature block) "")}
                "tool_use" {:type "toolCall"
                            :id (:id block)
                            :name (:name block)
                            :arguments (or (:input block) {})}
                ;; pass through unknown
                block))
            content))))

;; ── Stream Processing ─────────────────────────────────────────────────────────

(defn stream-messages
  "Send a prompt to Claude via CLI bridge. Returns promise of response state map.
   The CLI handles the full agent loop including tool execution.

   opts:
     :model       - model id
     :prompt      - user prompt string
     :system      - extra system prompt to append
     :max-tokens  - max output tokens
     :mcp-config  - path to MCP config JSON for custom tools
     :resume-session-id - session ID to resume
     :on-text     - callback (fn [text-delta])
     :on-thinking - callback (fn [thinking-delta])
     :on-tool-start - callback (fn [{:id :name}])
     :on-tool-result - callback (fn [{:name :content}])
     :on-error    - callback (fn [error-map])"
  [opts]
  (let [cli-args (build-cli-args (select-keys opts [:model :system
                                                     :mcp-config :resume-session-id]))
        callbacks (select-keys opts [:on-text :on-thinking :on-tool-start
                                     :on-tool-result :on-error])
        ;; Track which content we've already emitted callbacks for
        ;; (the CLI sends incremental assistant messages with cumulative content)
        emitted-text (atom "")
        emitted-blocks (atom #{})]

    (js/Promise.
     (fn [resolve reject]
       (let [proc (js/Bun.spawn
                   (clj->js cli-args)
                   #js {:stdout "pipe"
                        :stderr "pipe"
                        :stdin "pipe"
                        :cwd (.cwd js/process)
                        :env (unchecked-get js/process "env")})
             state (atom {:content []
                          :all-content []  ;; all assistant blocks across turns
                          :usage {}
                          :stop-reason nil
                          :model nil
                          :session-id nil
                          :done false
                          :result-text nil
                          :cost nil})
             buffer (atom "")]

         ;; Write prompt to stdin and close
         (.write (.-stdin proc) (str (:prompt opts)))
         (.end (.-stdin proc))

         ;; Read stdout line by line
         ;; Bun.spawn stdout is a ReadableStream directly (no .body)
         (let [reader (.getReader (.-stdout proc))
               decoder (js/TextDecoder.)]
           (letfn [(process-message [msg]
                     (case (:type msg)
                       ;; System init
                       "system"
                       (swap! state assoc
                              :model (:model msg)
                              :session-id (:session_id msg))

                       ;; Assistant message — extract and emit content deltas
                       "assistant"
                       (let [blocks (extract-content-blocks msg)
                             usage (get-in msg [:message :usage])
                             stop-reason (get-in msg [:message :stop_reason])]
                         (when blocks
                           (doseq [block blocks]
                             (let [block-id (or (:id block) (hash block))]
                               (when-not (contains? @emitted-blocks block-id)
                                 (swap! emitted-blocks conj block-id)
                                 (case (:type block)
                                   "text"
                                   (let [text (:text block)
                                         prev @emitted-text
                                         delta (if (str/starts-with? text prev)
                                                 (subs text (count prev))
                                                 text)]
                                     (when (and (seq delta) (:on-text callbacks))
                                       ((:on-text callbacks) delta))
                                     (reset! emitted-text text))

                                   "thinking"
                                   (when (:on-thinking callbacks)
                                     ((:on-thinking callbacks) (:thinking block)))

                                   "toolCall"
                                   (when (:on-tool-start callbacks)
                                     ((:on-tool-start callbacks)
                                      {:id (:id block)
                                       :name (:name block)
                                       :arguments (:arguments block)}))

                                   nil)))))
                         (swap! state (fn [s]
                                        (-> s
                                            (assoc :content (or blocks (:content s)))
                                            (update :all-content into (or blocks []))
                                            (cond->
                                              usage (assoc :usage usage)
                                              stop-reason (assoc :stop-reason stop-reason))))))

                       ;; User message — tool results from CLI's own execution
                       "user"
                       (let [content (get-in msg [:message :content])]
                         (when (and (sequential? content) (:on-tool-result callbacks))
                           (doseq [block content]
                             (when (= "tool_result" (:type block))
                               ((:on-tool-result callbacks)
                                {:name (:tool_use_id block)
                                 :content (:content block)
                                 :is-error (:is_error block)})))))

                       ;; Result — final summary
                       "result"
                       (swap! state assoc
                              :done true
                              :result-text (:result msg)
                              :stop-reason (or (:stop_reason msg) (:stop-reason @state))
                              :cost (:total_cost_usd msg)
                              :usage (or (:usage msg) (:usage @state)))

                       ;; Rate limit events
                       "rate_limit_event"
                       (let [info (:rate_limit_info msg)]
                         (when (and (= "rejected" (:status info)) (:on-error callbacks))
                           ((:on-error callbacks) {:type "rate_limit" :info info})))

                       ;; Ignore other types
                       nil))

                   (read-loop []
                     (-> (.read reader)
                         (.then
                          (fn [result]
                            (if (.-done result)
                              (do (swap! state assoc :done true)
                                  (resolve @state))
                              (let [chunk (.decode decoder (.-value result))
                                    text (str @buffer chunk)
                                    lines (.split text "\n")]
                                (reset! buffer (aget lines (dec (.-length lines))))
                                (doseq [line (butlast lines)]
                                  (when-let [msg (parse-json-line line)]
                                    (process-message msg)))
                                (read-loop)))))
                         (.catch reject)))]
             (read-loop)))

         ;; Capture stderr
         (-> (.text (.-stderr proc))
             (.then (fn [stderr-text]
                      (when (and (seq stderr-text) (not (:done @state)))
                        (swap! state assoc :error stderr-text))))))))))

(defn response->assistant-message
  "Convert streamed response state to a normalized assistant message map."
  [state]
  {:role "assistant"
   :content (or (:content state) [])
   :model (:model state)
   :usage (:usage state)
   :stop-reason (:stop-reason state)})
