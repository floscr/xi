(ns xi.provider.ollama
  "Direct OpenAI-compatible streaming provider for Ollama (and similar).
   Manages conversation history, tool-use loop, and callbacks.
   No bridge — raw HTTP to the model API."
  (:require [clojure.string :as str]
            [xi.ext.core :as ext]
            [xi.tools.registry :as tools]
            [xi.system-prompt :as system-prompt]))

;; ── Config ──────────────────────────────────────────────────────────────────

(def ^:private OLLAMA_BASE_URL
  (or (aget js/process.env "OLLAMA_BASE_URL")
      "http://localhost:11434"))

;; ── Tool Definitions → OpenAI Format ────────────────────────────────────────

(defn- xi-tool->openai
  "Convert Xi tool definition (Anthropic format) to OpenAI function-calling format."
  [{:keys [name description input_schema]}]
  {:type "function"
   :function {:name name
              :description description
              :parameters (or input_schema {:type "object" :properties {}})}})

(defn- build-tools []
  (mapv xi-tool->openai (tools/tool-definitions)))

;; ── Tool Execution ──────────────────────────────────────────────────────────

(defn- execute-tool-call
  "Execute a single tool call through Xi's registry + permission gate.
   Returns promise of {:role \"tool\" :tool_call_id ... :content ...}"
  [^js tool-call cwd]
  (let [registry (tools/tool-registry)
        ^js func (.-function tool-call)
        tool-name (.-name func)
        args-str (.-arguments func)
        tool-call-id (.-id tool-call)
        args (try (js->clj (js/JSON.parse args-str) :keywordize-keys true)
                  (catch :default _ {}))
        gated (ext/dispatch-hook-transform
               :tool-call {:name tool-name :arguments args} {:cwd cwd})]
    (if (nil? gated)
      (js/Promise.resolve
       {:role "tool"
        :tool_call_id tool-call-id
        :content "Blocked by Xi permission gate"})
      (let [exec-fn (get registry tool-name)]
        (if exec-fn
          (-> (let [result (exec-fn args {:cwd cwd})]
                (if (instance? js/Promise result) result (js/Promise.resolve result)))
              (.then (fn [result]
                       {:role "tool"
                        :tool_call_id tool-call-id
                        :content (let [c (:content result)]
                                   (cond
                                     (string? c) c
                                     (sequential? c) (->> c
                                                          (keep #(when (= "text" (:type %)) (:text %)))
                                                          (str/join "\n"))
                                     :else (str c)))}))
              (.catch (fn [err]
                        {:role "tool"
                         :tool_call_id tool-call-id
                         :content (str "Tool error: " (.-message err))})))
          (js/Promise.resolve
           {:role "tool"
            :tool_call_id tool-call-id
            :content (str "Unknown tool: " tool-name)}))))))

;; ── SSE Stream Parsing ──────────────────────────────────────────────────────

(defn- parse-sse-line [line]
  (when (str/starts-with? line "data: ")
    (let [data (subs line 6)]
      (when (not= data "[DONE]")
        (try (js/JSON.parse data)
             (catch :default _ nil))))))

;; ── Core Streaming ──────────────────────────────────────────────────────────

(defn- process-chunk-delta!
  "Process a single SSE chunk. Mutates state atom, fires callbacks."
  [^js chunk state on-text on-thinking]
  (let [^js choice (aget (.-choices chunk) 0)]
    (when choice
      (when-let [fr (.-finish_reason choice)]
        (swap! state assoc :finish-reason fr))
      (when-let [^js delta (.-delta choice)]
        ;; Text content
        (when-let [content (.-content delta)]
          (when (and (string? content) (seq content))
            (swap! state update :text str content)
            (when on-text (on-text content))))
        ;; Reasoning/thinking (qwen3, deepseek, etc.)
        (when-let [reasoning (.-reasoning delta)]
          (when (and (string? reasoning) (seq reasoning))
            (when on-thinking (on-thinking reasoning))))
        ;; Tool calls
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
  "Consume a ReadableStream of SSE events. Returns promise of final state."
  [^js body state on-text on-thinking]
  (let [^js reader (.getReader body)
        decoder (js/TextDecoder.)
        line-buf (atom "")]
    (js/Promise.
     (fn [resolve reject]
       (letfn [(read-chunk []
                 (-> (.read reader)
                     (.then
                      (fn [^js result]
                        (if (.-done result)
                          (resolve @state)
                          (let [text (.decode decoder (.-value result) #js {:stream true})
                                combined (str @line-buf text)
                                lines (.split combined "\n")]
                            (reset! line-buf (.pop lines))
                            (doseq [line lines]
                              (when-let [^js chunk (parse-sse-line line)]
                                (process-chunk-delta! chunk state on-text on-thinking)))
                            (read-chunk)))))
                     (.catch reject)))]
         (read-chunk))))))

(defn- stream-chat-completion
  "Make one streaming request to the OpenAI-compatible endpoint.
   Returns promise of {:text str :tool-calls {...} :usage {...}}."
  [messages openai-tools {:keys [model on-text on-thinking abort-signal]}]
  (let [url (str OLLAMA_BASE_URL "/v1/chat/completions")
        body (cond-> {:model model
                      :messages (clj->js messages)
                      :stream true}
               (seq openai-tools) (assoc :tools (clj->js openai-tools)))
        state (atom {:text ""
                     :tool-calls {}
                     :usage nil
                     :finish-reason nil})]
    (-> (js/fetch url
                  #js {:method "POST"
                       :headers #js {"Content-Type" "application/json"}
                       :body (js/JSON.stringify (clj->js body))
                       :signal (when abort-signal
                                 (let [ac (js/AbortController.)]
                                   (add-watch abort-signal ::abort
                                              (fn [_ _ _ v] (when v (.abort ac))))
                                   (.-signal ac)))})
        (.then
         (fn [^js response]
           (when-not (.-ok response)
             (throw (js/Error. (str "Ollama API error: " (.-status response)
                                    " " (.-statusText response)))))
           (read-sse-stream (.-body response) state on-text on-thinking)))
        (.catch
         (fn [err]
           (throw (js/Error. (str "Ollama request failed: " (.-message err)))))))))

;; ── Agent Turn Loop ─────────────────────────────────────────────────────────

(defn stream-messages
  "Run a full agent turn with tool-use loop against an OpenAI-compatible API.
   Same callback interface as provider/stream-messages.
   Returns promise of response state map."
  [opts]
  (let [callbacks (select-keys opts [:on-text :on-thinking :on-tool-start
                                     :on-tool-args :on-tool-result :on-error])
        cwd (or (:cwd opts) (.cwd js/process))
        model (:model opts)
        openai-tools (build-tools)

        ;; Build system prompt
        system-text (let [tool-defs (tools/tool-definitions)
                          base (system-prompt/build tool-defs cwd)]
                      (if (:system opts)
                        (str base "\n\n" (:system opts))
                        base))

        ;; Conversation history — managed here (no bridge)
        messages (atom [{:role "system" :content system-text}
                        {:role "user" :content (:prompt opts)}])
        total-usage (atom {:input_tokens 0 :output_tokens 0})
        max-iterations 25]

    (js/Promise.
     (fn [resolve _reject]
       (letfn [(iterate-turn [n]
                 (if (>= n max-iterations)
                   (resolve {:content [] :usage @total-usage :model model
                             :stop-reason "max_iterations" :done true})
                   (-> (stream-chat-completion @messages openai-tools
                                               {:model model
                                                :on-text (:on-text callbacks)
                                                :on-thinking (:on-thinking callbacks)
                                                :abort-signal (:abort-signal opts)})
                       (.then (fn [result]
                                (when-let [u (:usage result)]
                                  (swap! total-usage update :input_tokens + (:input_tokens u 0))
                                  (swap! total-usage update :output_tokens + (:output_tokens u 0)))
                                (let [tool-calls (:tool-calls result)
                                      has-tools? (seq tool-calls)]
                                  (if-not has-tools?
                                    ;; Done — no tool calls
                                    (resolve {:content [{:type "text" :text (:text result)}]
                                              :usage @total-usage
                                              :model model
                                              :stop-reason "stop"
                                              :result-text (:text result)
                                              :done true})
                                    ;; Tool calls — execute and loop
                                    (let [sorted-tcs (sort-by first tool-calls)
                                          tc-entries (mapv (fn [[_idx tc]]
                                                            (let [args-str (apply str (:arguments-chunks tc))]
                                                              {:id (:id tc)
                                                               :name (:name tc)
                                                               :arguments-str args-str
                                                               :arguments (try (js->clj (js/JSON.parse args-str)
                                                                                        :keywordize-keys true)
                                                                               (catch :default _ {}))}))
                                                          sorted-tcs)]
                                      ;; Emit tool callbacks
                                      (doseq [tc tc-entries]
                                        (when (:on-tool-start callbacks)
                                          ((:on-tool-start callbacks)
                                           {:id (:id tc) :name (:name tc) :arguments (:arguments tc)}))
                                        (when (:on-tool-args callbacks)
                                          ((:on-tool-args callbacks)
                                           {:id (:id tc) :name (:name tc) :arguments (:arguments tc)})))
                                      ;; Add assistant message to history
                                      (swap! messages conj
                                             {:role "assistant"
                                              :content (when (seq (:text result)) (:text result))
                                              :tool_calls (mapv (fn [tc]
                                                                  {:id (:id tc)
                                                                   :type "function"
                                                                   :function {:name (:name tc)
                                                                              :arguments (:arguments-str tc)}})
                                                                tc-entries)})
                                      ;; Execute tools
                                      (-> (js/Promise.all
                                           (clj->js
                                            (mapv (fn [tc]
                                                    (execute-tool-call
                                                     (clj->js {:id (:id tc)
                                                                :type "function"
                                                                :function {:name (:name tc)
                                                                           :arguments (:arguments-str tc)}})
                                                     cwd))
                                                  tc-entries)))
                                          (.then (fn [results]
                                                   (let [tool-results (js->clj results :keywordize-keys true)]
                                                     (doseq [[tc tr] (map vector tc-entries tool-results)]
                                                       (when (:on-tool-result callbacks)
                                                         ((:on-tool-result callbacks)
                                                          {:name (:name tc)
                                                           :content (:content tr)
                                                           :is-error false})))
                                                     (doseq [tr tool-results]
                                                       (swap! messages conj tr))
                                                     (iterate-turn (inc n)))))))))))
                       (.catch (fn [err]
                                 (when (:on-error callbacks)
                                   ((:on-error callbacks)
                                    {:type "error" :message (.-message err)}))
                                 (resolve {:content [] :usage @total-usage :model model
                                           :stop-reason "error" :done true}))))))]
         (iterate-turn 0))))))

(defn response->assistant-message [state]
  {:role "assistant"
   :content (or (:content state) [])
   :model (:model state)
   :usage (:usage state)
   :stop-reason (:stop-reason state)})
