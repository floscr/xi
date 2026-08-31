(ns xi.provider.openai-compat
  "Shared OpenAI-compatible streaming provider core.
   Speaks the OpenAI `/chat/completions` SSE wire format: streams text +
   reasoning deltas, accumulates tool calls, and drives Xi's tool-use loop.

   No app-state dependency — callers pass a config map and per-turn opts.
   Both `xi.provider.ollama` and `xi.provider.zen` (chat-completions surface)
   are thin wrappers over `stream-messages` here.

   config keys:
     :base-url   e.g. \"http://localhost:11434\" or \"https://opencode.ai/zen/v1\"
     :chat-path  path appended to base-url for the endpoint
                 (default \"/v1/chat/completions\"; Zen passes \"/chat/completions\")
     :headers    extra request headers (e.g. auth); merged over Content-Type
     :label      short name used in error messages (e.g. \"Ollama\", \"Zen\")
     :pre-flight (fn [] → Promise) run once before the turn (e.g. start Ollama);
                 optional, defaults to an immediately-resolved promise
     :model-id   (fn [model] → str) transform the model id before sending
                 (e.g. strip an `opencode/` prefix); optional, identity default"
  (:require [clojure.string :as str]
            [xi.tools.registry :as tools]
            [xi.system-prompt :as system-prompt]
            [xi.util :as util]))

;; ── Tool Definitions → OpenAI Format ────────────────────────────────────────

(defn- xi-tool->openai
  [{:keys [name description input_schema]}]
  {:type "function"
   :function {:name name
              :description description
              :parameters (or input_schema {:type "object" :properties {}})}})

(defn- build-tools []
  (mapv xi-tool->openai (tools/tool-definitions)))

;; ── Tool Execution ──────────────────────────────────────────────────────────

(def ^:private default-gate
  (fn [tool-call] (js/Promise.resolve tool-call)))

(defn- execute-tool-call
  "Execute a single tool call through Xi's registry + tool gate.
   Returns promise of {:role \"tool\" :tool_call_id ... :content ...}"
  [{:keys [id name arguments]} cwd tool-gate]
  (let [registry (tools/tool-registry)]
    (-> (tool-gate {:name name :arguments arguments})
        (.then
         (fn [gated]
           (if (nil? gated)
             {:role "tool" :tool_call_id id
              :content "Blocked by Xi permission gate"}
             (let [exec-fn (get registry name)]
               (if exec-fn
                 (-> (tools/run-tool exec-fn arguments {:cwd cwd})
                     (.then (fn [{:keys [content]}]
                              {:role "tool" :tool_call_id id
                               :content (util/extract-text-content content)})))
                 {:role "tool" :tool_call_id id
                  :content (str "Unknown tool: " name)}))))))))

;; ── SSE Stream Parsing ──────────────────────────────────────────────────────

(defn- parse-sse-line [line]
  (when (str/starts-with? line "data: ")
    (let [data (subs line 6)]
      (when (not= data "[DONE]")
        (try (js/JSON.parse data)
             (catch :default _ nil))))))

;; ── Core Streaming ──────────────────────────────────────────────────────────

(defn- process-chunk-delta!
  "Process a single SSE chunk. Accumulates into the per-request state atom."
  [^js chunk state on-text on-thinking]
  (let [^js choice (aget (.-choices chunk) 0)]
    (when choice
      (when-let [fr (.-finish_reason choice)]
        (swap! state assoc :finish-reason fr))
      (when-let [^js delta (.-delta choice)]
        (when-let [content (.-content delta)]
          (when (and (string? content) (seq content))
            (swap! state update :text str content)
            (when on-text (on-text content))))
        ;; Reasoning/thinking (qwen3, deepseek, etc.)
        (when-let [reasoning (.-reasoning delta)]
          (when (and (string? reasoning) (seq reasoning))
            (when on-thinking (on-thinking reasoning))))
        (when-let [^js tcs (.-tool_calls delta)]
          (doseq [^js tc tcs]
            (let [idx (.-index tc)]
              (when-let [^js func (.-function tc)]
                (when (.-name func)
                  (swap! state assoc-in [:tool-calls idx]
                         {:id (or (.-id tc) (str "call_" (random-uuid)))
                          :name (.-name func)
                          :arguments-chunks []}))
                (when-let [args-chunk (.-arguments func)]
                  (swap! state update-in [:tool-calls idx :arguments-chunks]
                         conj args-chunk)))))))
      (when-let [^js usage (.-usage chunk)]
        (swap! state assoc :usage
               {:input_tokens (or (.-prompt_tokens usage) 0)
                :output_tokens (or (.-completion_tokens usage) 0)})))))

(defn- read-sse-stream
  [^js body state on-text on-thinking]
  (let [^js reader (.getReader body)
        decoder (js/TextDecoder.)
        buf #js {:line ""}]
    (js/Promise.
     (fn [resolve reject]
       (letfn [(read-chunk []
                 (-> (.read reader)
                     (.then
                      (fn [^js result]
                        (if (.-done result)
                          (resolve @state)
                          (let [text (.decode decoder (.-value result) #js {:stream true})
                                combined (str (.-line buf) text)
                                lines (.split combined "\n")]
                            (set! (.-line buf) (.pop lines))
                            (doseq [line lines]
                              (when-let [^js chunk (parse-sse-line line)]
                                (process-chunk-delta! chunk state on-text on-thinking)))
                            (read-chunk)))))
                     (.catch reject)))]
         (read-chunk))))))

(defn- stream-chat-completion
  "One streaming request to the OpenAI-compatible endpoint.
   Returns promise of {:text str :tool-calls {...} :usage {...}}."
  [messages openai-tools
   {:keys [model on-text on-thinking abort-signal base-url chat-path headers label]}]
  (let [url (str base-url chat-path)
        body (cond-> {:model model
                      :messages (clj->js messages)
                      :stream true}
               (seq openai-tools) (assoc :tools (clj->js openai-tools)))
        req-headers (clj->js (merge {"Content-Type" "application/json"} headers))
        ;; Per-request stream accumulation — contained to this request.
        state (atom {:text ""
                     :tool-calls {}
                     :usage nil
                     :finish-reason nil})]
    (-> (js/fetch url
                  #js {:method "POST"
                       :headers req-headers
                       :body (js/JSON.stringify (clj->js body))
                       :signal abort-signal})
        (.then
         (fn [^js response]
           (when-not (.-ok response)
             (throw (js/Error. (str label " API error: " (.-status response)
                                    " " (.-statusText response)))))
           (read-sse-stream (.-body response) state on-text on-thinking)))
        (.catch
         (fn [err]
           (throw (js/Error. (str label " request failed: " (.-message err)))))))))

;; ── Agent Turn Loop ─────────────────────────────────────────────────────────

(def ^:private max-iterations 25)

(defn stream-messages
  "Run a full agent turn with tool-use loop against an OpenAI-compatible API.
   `config` selects the endpoint/auth/label; `opts` are the per-turn callbacks
   and prompt. Returns {:promise p :abort! f}."
  [config opts]
  (let [{:keys [base-url chat-path headers label pre-flight model-id]
         :or   {chat-path "/v1/chat/completions"
                headers   {}
                label     "OpenAI"
                pre-flight (fn [] (js/Promise.resolve true))
                model-id  identity}} config
        callbacks (select-keys opts [:on-text :on-thinking :on-tool-start
                                     :on-tool-args :on-tool-result :on-error])
        cwd (or (:cwd opts) (.cwd js/process))
        model (model-id (:model opts))
        tool-gate (or (:tool-gate opts) default-gate)
        openai-tools (build-tools)

        system-text (let [tool-defs (tools/tool-definitions)
                          base (system-prompt/build tool-defs cwd)]
                      (if (:system opts)
                        (str base "\n\n" (:system opts))
                        base))

        abort-controller (js/AbortController.)
        flags #js {:aborted false}

        ;; Per-turn conversation — managed here (no bridge). Contained.
        !messages (atom [{:role "system" :content system-text}
                         {:role "user" :content (:prompt opts)}])
        !usage (atom {:input_tokens 0 :output_tokens 0})

        promise
        (js/Promise.
         (fn [resolve _reject]
           (letfn [(finish [extra]
                     (resolve (merge {:usage @!usage :model model
                                      :aborted (.-aborted flags) :done true}
                                     extra)))

                   (iterate-turn [n]
                     (cond
                       (.-aborted flags)
                       (finish {:content [] :stop-reason "aborted"})

                       (>= n max-iterations)
                       (finish {:content [] :stop-reason "max_iterations"})

                       :else
                       (-> (stream-chat-completion @!messages openai-tools
                                                   {:model model
                                                    :on-text (:on-text callbacks)
                                                    :on-thinking (:on-thinking callbacks)
                                                    :abort-signal (.-signal abort-controller)
                                                    :base-url base-url
                                                    :chat-path chat-path
                                                    :headers headers
                                                    :label label})
                           (.then (fn [result] (handle-result result n)))
                           (.catch (fn [err]
                                     (when (and (:on-error callbacks)
                                                (not (.-aborted flags)))
                                       ((:on-error callbacks)
                                        {:type "error" :message (.-message err)}))
                                     (finish {:content [] :stop-reason "error"}))))))

                   (handle-result [result n]
                     (when-let [u (:usage result)]
                       (swap! !usage (fn [t]
                                       (-> t
                                           (update :input_tokens + (:input_tokens u 0))
                                           (update :output_tokens + (:output_tokens u 0))))))
                     (if-not (seq (:tool-calls result))
                       ;; Done — no tool calls
                       (finish {:content [{:type "text" :text (:text result)}]
                                :stop-reason "stop"
                                :result-text (:text result)})
                       ;; Tool calls — execute and loop
                       (let [tc-entries (mapv (fn [[_idx tc]]
                                                (let [args-str (apply str (:arguments-chunks tc))]
                                                  {:id (:id tc)
                                                   :name (:name tc)
                                                   :arguments-str args-str
                                                   :arguments (try (js->clj (js/JSON.parse args-str)
                                                                            :keywordize-keys true)
                                                                   (catch :default _ {}))}))
                                              (sort-by first (:tool-calls result)))]
                         (doseq [tc tc-entries]
                           (when (:on-tool-start callbacks)
                             ((:on-tool-start callbacks)
                              {:id (:id tc) :name (:name tc) :arguments (:arguments tc)}))
                           (when (:on-tool-args callbacks)
                             ((:on-tool-args callbacks)
                              {:id (:id tc) :name (:name tc) :arguments (:arguments tc)})))
                         (swap! !messages conj
                                {:role "assistant"
                                 :content (when (seq (:text result)) (:text result))
                                 :tool_calls (mapv (fn [tc]
                                                     {:id (:id tc)
                                                      :type "function"
                                                      :function {:name (:name tc)
                                                                 :arguments (:arguments-str tc)}})
                                                   tc-entries)})
                         (-> (js/Promise.all
                              (clj->js (mapv #(execute-tool-call % cwd tool-gate) tc-entries)))
                             (.then (fn [results]
                                      (let [tool-results (js->clj results :keywordize-keys true)]
                                        (doseq [[tc tr] (map vector tc-entries tool-results)]
                                          (when (:on-tool-result callbacks)
                                            ((:on-tool-result callbacks)
                                             {:id (:id tc)
                                              :content (:content tr)
                                              :is-error false}))
                                          (swap! !messages conj tr))
                                        (iterate-turn (inc n)))))))))]
             (-> (pre-flight)
                 (.then (fn [_] (iterate-turn 0)))
                 (.catch (fn [err]
                           (when (:on-error callbacks)
                             ((:on-error callbacks)
                              {:type "error" :message (.-message err)}))
                           (finish {:content [] :stop-reason "error"})))))))]

    {:promise promise
     :abort!  (fn []
                (set! (.-aborted flags) true)
                (.abort abort-controller))}))
