(ns xi.providers.zen.anthropic
  "OpenCode Zen Anthropic Messages surface (Claude + Qwen models).

   Raw HTTP to POST https://opencode.ai/zen/v1/messages — the standard
   Anthropic Messages streaming SSE wire format. Auth is `x-api-key` (the Zen
   Anthropic surface rejects bearer-only). Xi drives its own tool-use loop:
   tools are exposed in Anthropic `input_schema` form, tool_use blocks are
   accumulated from `input_json_delta`, executed through Xi's registry + gate,
   and fed back as `tool_result` content blocks.

   Interface: (stream-messages config opts) → {:promise :abort!}."
  (:require [clojure.string :as str]
            [xi.agent :as agent]
            [xi.tools.registry :as tools]
            [xi.system-prompt :as system-prompt]
            [xi.util :as util]))

;; ── History replay → Anthropic messages ───────────────────────────────────

(defn history->anthropic-messages
  "Convert the neutral message list from `xi.agent/history->messages` into
   Anthropic Messages so a sessionless turn replays prior conversation.
   Assistant tool-calls become `tool_use` content blocks; tool results become
   `{:role \"user\"}` messages carrying `tool_result` blocks."
  [neutral]
  (mapv
   (fn [{:keys [role text tool-calls results]}]
     (case role
       :user      {:role "user" :content text}
       :assistant {:role "assistant"
                   :content (into (if (seq text)
                                    [{:type "text" :text text}]
                                    [])
                                  (mapv (fn [{:keys [id name arguments]}]
                                          {:type "tool_use"
                                           :id id
                                           :name name
                                           :input (or arguments {})})
                                        tool-calls))}
       :tool      {:role "user"
                   :content (mapv (fn [{:keys [id content is-error]}]
                                    {:type "tool_result"
                                     :tool_use_id id
                                     :content (str content)
                                     :is_error (boolean is-error)})
                                  results)}))
   neutral))

(def ^:private max-iterations 25)
(def ^:private max-tokens 32000)

(def ^:private default-gate
  (fn [tool-call] (js/Promise.resolve tool-call)))

;; ── Tool defs → Anthropic format ─────────────────────────────────────────────

(defn- xi-tool->anthropic
  [{:keys [name description input_schema]}]
  {:name name
   :description description
   :input_schema (or input_schema {:type "object" :properties {}})})

(defn- build-tools []
  (mapv xi-tool->anthropic (tools/tool-definitions)))

;; ── Prompt caching ───────────────────────────────────────────────────────────
;;
;; Anthropic prompt caching caches the request prefix up to each block marked
;; with `cache_control`. Xi's tool-use loop re-sends the same large static
;; prefix (system prompt + tool defs + prior turns) on every iteration; without
;; caching each round-trip pays full input price for all of it. We place
;; breakpoints on the biggest static spans so repeat iterations hit the cache
;; at ~10% of the input price:
;;   1. the system prompt (large, fixed for the whole turn)
;;   2. the tool definitions (fixed for the whole turn)
;;   3. the tail of the conversation so far (grows by one turn each iteration —
;;      the previous turns are already cached, so only the newest is full-price)
;; Anthropic allows up to 4 breakpoints per request; we use at most 3.

(def ^:private cache-control {:type "ephemeral"})

(defn system->blocks
  "Turn a plain system string into a single cached text block. A block array
   lets us attach cache_control; a bare string can't be cached."
  [system]
  (when (and (string? system) (pos? (count system)))
    [{:type "text" :text system :cache_control cache-control}]))

(defn cache-last-tool
  "Mark the final tool definition with cache_control so the whole (static) tool
   list is cached as one prefix span."
  [tools]
  (if (seq tools)
    (conj (vec (butlast tools))
          (assoc (last tools) :cache_control cache-control))
    tools))

(defn- block-with-cache
  "Attach cache_control to a content block. String content is first promoted to
   a text block so the marker has somewhere to live."
  [block]
  (cond
    (string? block) {:type "text" :text block :cache_control cache-control}
    (map? block)    (assoc block :cache_control cache-control)
    :else           block))

(defn cache-conversation
  "Add a cache breakpoint at the very end of the message list: the last content
   block of the last message. Everything before it (all prior turns) becomes a
   cacheable prefix, so each tool-loop iteration only pays full price for the
   newest turn instead of re-billing the whole history."
  [messages]
  (if-let [last-msg (peek (vec messages))]
    (let [content (:content last-msg)
          content' (cond
                     (string? content) [(block-with-cache content)]
                     (sequential? content)
                     (conj (vec (butlast content))
                           (block-with-cache (last content)))
                     :else content)]
      (conj (vec (butlast messages)) (assoc last-msg :content content')))
    messages))

;; ── Tool execution ───────────────────────────────────────────────────────────

(defn- execute-tool-call
  "Execute one tool_use through Xi's registry + gate. Returns promise of an
   Anthropic tool_result content block."
  [{:keys [id name arguments]} cwd tool-gate]
  (let [registry (tools/tool-registry)]
    (-> (tool-gate {:name name :arguments arguments})
        (.then
         (fn [gated]
           (if (nil? gated)
             {:type "tool_result" :tool_use_id id
              :content "Blocked by Xi permission gate" :is_error true}
             (let [exec-fn (get registry name)]
               (if exec-fn
                 (-> (tools/run-tool exec-fn arguments {:cwd cwd})
                     (.then (fn [{:keys [content is-error]}]
                              {:type "tool_result" :tool_use_id id
                               :content (util/extract-text-content content)
                               :is_error (boolean is-error)})))
                 {:type "tool_result" :tool_use_id id
                  :content (str "Unknown tool: " name) :is_error true}))))))))

;; ── SSE parsing ──────────────────────────────────────────────────────────────

(defn- parse-sse-data [line]
  (when (str/starts-with? line "data: ")
    (let [data (subs line 6)]
      (try (js/JSON.parse data)
           (catch :default _ nil)))))

(defn- process-event!
  "Fold one Anthropic stream event into the per-request state atom + fire
   text/thinking callbacks."
  [^js ev state on-text on-thinking]
  (case (.-type ev)
    "message_start"
    (when-let [^js u (some-> ev .-message .-usage)]
      (swap! state assoc :usage
             {:input_tokens (or (.-input_tokens u) 0)
              :output_tokens (or (.-output_tokens u) 0)
              :cache_creation_input_tokens (or (.-cache_creation_input_tokens u) 0)
              :cache_read_input_tokens (or (.-cache_read_input_tokens u) 0)}))

    "content_block_start"
    (let [idx (.-index ev)
          ^js block (.-content_block ev)]
      (when (= "tool_use" (.-type block))
        (swap! state assoc-in [:tool-blocks idx]
               {:id (.-id block)
                :name (.-name block)
                :json-chunks []})))

    "content_block_delta"
    (let [idx (.-index ev)
          ^js delta (.-delta ev)]
      (case (.-type delta)
        "text_delta"     (when on-text (on-text (.-text delta)))
        "thinking_delta" (when on-thinking (on-thinking (.-thinking delta)))
        "input_json_delta"
        (swap! state update-in [:tool-blocks idx :json-chunks]
               conj (.-partial_json delta))
        nil))

    "message_delta"
    (do
      (when-let [^js d (.-delta ev)]
        (when-let [sr (.-stop_reason d)]
          (swap! state assoc :stop-reason sr)))
      (let [^js u (.-usage ev)]
        (when u
          (swap! state update :usage
                 (fn [cur]
                   (merge (or cur {})
                          (cond-> {}
                            (.-input_tokens u)  (assoc :input_tokens (.-input_tokens u))
                            (.-output_tokens u) (assoc :output_tokens (.-output_tokens u)))))))))

    nil))

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
                              (when-let [^js ev (parse-sse-data line)]
                                (process-event! ev state on-text on-thinking)))
                            (read-chunk)))))
                     (.catch reject)))]
         (read-chunk))))))

;; ── One streaming request ────────────────────────────────────────────────────

(defn- stream-once
  "One Messages request. Returns promise of
   {:text str :tool-calls [{:id :name :arguments}] :usage {...}}."
  [messages system anthropic-tools
   {:keys [model api-key base-url on-text on-thinking abort-signal]}]
  (let [!text (atom "")
        state (atom {:tool-blocks {} :usage nil :stop-reason nil})
        on-text* (fn [t] (swap! !text str t) (when on-text (on-text t)))
        body (cond-> {:model model
                      :max_tokens max-tokens
                      :stream true
                      :messages (clj->js (cache-conversation messages))}
               system              (assoc :system (clj->js (system->blocks system)))
               (seq anthropic-tools) (assoc :tools (clj->js (cache-last-tool anthropic-tools))))]
    (-> (js/fetch (str base-url "/messages")
                  #js {:method "POST"
                       :headers #js {"Content-Type" "application/json"
                                     "x-api-key" api-key
                                     "anthropic-version" "2023-06-01"}
                       :body (js/JSON.stringify (clj->js body))
                       :signal abort-signal})
        (.then
         (fn [^js response]
           (when-not (.-ok response)
             (throw (js/Error. (str "Zen (messages) API error: " (.-status response)
                                    " " (.-statusText response)))))
           (read-sse-stream (.-body response) state on-text* on-thinking)))
        (.then
         (fn [final]
           (let [tool-calls (->> (:tool-blocks final)
                                 (sort-by first)
                                 (mapv (fn [[_idx tb]]
                                         (let [json-str (apply str (:json-chunks tb))]
                                           {:id (:id tb)
                                            :name (:name tb)
                                            :arguments (try (js->clj (js/JSON.parse json-str)
                                                                     :keywordize-keys true)
                                                            (catch :default _ {}))}))))]
             {:text @!text
              :tool-calls tool-calls
              :stop-reason (:stop-reason final)
              :usage (:usage final)})))
        (.catch
         (fn [err]
           (throw (js/Error. (str "Zen (messages) request failed: " (.-message err)))))))))

;; ── Turn loop ────────────────────────────────────────────────────────────────

(defn stream-messages
  "Run a full agent turn against the Zen Anthropic Messages surface.
   `config` = {:api-key :base-url}. Returns {:promise :abort!}."
  [{:keys [api-key base-url]} opts]
  (let [callbacks (select-keys opts [:on-text :on-thinking :on-tool-start
                                     :on-tool-args :on-tool-result :on-error])
        cwd (or (:cwd opts) (.cwd js/process))
        model (:model opts)
        tool-gate (or (:tool-gate opts) default-gate)
        anthropic-tools (build-tools)

        system-text (let [tool-defs (tools/tool-definitions)
                          base (system-prompt/build tool-defs cwd)]
                      (if (:system opts)
                        (str base "\n\n" (:system opts))
                        base))

        abort-controller (js/AbortController.)
        flags #js {:aborted false}

        ;; Sessionless surface — replay the room's prior history ahead of the
        ;; current prompt so the model keeps context across turns.
        prior-msgs (some-> (:history opts)
                           agent/history->messages
                           history->anthropic-messages)
        !messages (atom (conj (vec prior-msgs)
                              {:role "user" :content (:prompt opts)}))
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
                       (-> (stream-once @!messages system-text anthropic-tools
                                        {:model model
                                         :api-key api-key
                                         :base-url base-url
                                         :on-text (:on-text callbacks)
                                         :on-thinking (:on-thinking callbacks)
                                         :abort-signal (.-signal abort-controller)})
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
                                           (update :output_tokens + (:output_tokens u 0))
                                           (update :cache_creation_input_tokens (fnil + 0) (:cache_creation_input_tokens u 0))
                                           (update :cache_read_input_tokens (fnil + 0) (:cache_read_input_tokens u 0))))))
                     (if-not (seq (:tool-calls result))
                       ;; No tool calls. If the model was cut off at max_tokens
                       ;; (a truncated response — the classic "it just stopped"
                       ;; mid-thought), append the partial text and re-request so
                       ;; it continues, instead of silently ending the turn.
                       (if (and (= "max_tokens" (:stop-reason result))
                                (seq (:text result))
                                (< (inc n) max-iterations))
                         (do
                           (swap! !messages conj
                                  {:role "assistant"
                                   :content [{:type "text" :text (:text result)}]})
                           (iterate-turn (inc n)))
                         (finish {:content [{:type "text" :text (:text result)}]
                                  :stop-reason "stop"
                                  :result-text (:text result)}))
                       (let [tcs (:tool-calls result)]
                         (doseq [tc tcs]
                           (when (:on-tool-start callbacks)
                             ((:on-tool-start callbacks)
                              {:id (:id tc) :name (:name tc) :arguments (:arguments tc)}))
                           (when (:on-tool-args callbacks)
                             ((:on-tool-args callbacks)
                              {:id (:id tc) :name (:name tc) :arguments (:arguments tc)})))
                         ;; assistant turn: text + tool_use blocks
                         (swap! !messages conj
                                {:role "assistant"
                                 :content (into (if (seq (:text result))
                                                  [{:type "text" :text (:text result)}]
                                                  [])
                                                (mapv (fn [tc]
                                                        {:type "tool_use"
                                                         :id (:id tc)
                                                         :name (:name tc)
                                                         :input (:arguments tc)})
                                                      tcs))})
                         (-> (js/Promise.all
                              (clj->js (mapv #(execute-tool-call % cwd tool-gate) tcs)))
                             (.then
                              (fn [results]
                                (let [tool-results (js->clj results :keywordize-keys true)]
                                  (doseq [[tc tr] (map vector tcs tool-results)]
                                    (when (:on-tool-result callbacks)
                                      ((:on-tool-result callbacks)
                                       {:id (:id tc)
                                        :content (:content tr)
                                        :is-error (:is_error tr)})))
                                  ;; user turn: tool_result blocks
                                  (swap! !messages conj
                                         {:role "user"
                                          :content (mapv (fn [tr]
                                                           {:type "tool_result"
                                                            :tool_use_id (:tool_use_id tr)
                                                            :content (:content tr)
                                                            :is_error (:is_error tr)})
                                                         tool-results)})
                                  (iterate-turn (inc n)))))))))]
             (iterate-turn 0))))]

    {:promise promise
     :abort!  (fn []
                (set! (.-aborted flags) true)
                (.abort abort-controller))}))
