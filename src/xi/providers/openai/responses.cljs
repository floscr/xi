(ns xi.providers.openai.responses
  "Shared OpenAI Responses-API adapter (streaming SSE + Xi's own tool-use loop).

   Both the OpenCode Zen Responses surface (xi.providers.zen.responses) and the
   ChatGPT-subscription Codex surface (xi.providers.openai.codex) speak the same
   OpenAI Responses wire format; only the endpoint, auth headers, and request
   body differ. This namespace owns everything they share:

     - history replay → Responses `input` items
     - Xi tool defs → Responses `function` defs, execution through the registry
       + permission gate, results fed back as `function_call_output` items
     - SSE stream parsing (text / reasoning deltas, output-item capture, usage)
     - the stateless (`store:false`) multi-iteration tool loop, which echoes the
       model's own output items back into the next request's `input` verbatim so
       message / reasoning / function_call pairing + ids stay valid.

   The caller supplies a `:build-request` hook — an async fn
   `(fn [{:keys [model input system tools reasoning-effort]}]
      → promise of {:url :headers :body}]` — that owns the provider-specific
   endpoint, headers (incl. token refresh), and body shape (where the system
   prompt lives, extra fields, etc.). See `stream-messages`."
  (:require [clojure.string :as str]
            [xi.agent :as agent]
            [xi.tools.registry :as tools]
            [xi.system-prompt :as system-prompt]
            [xi.util :as util]))

(def ^:private max-iterations 25)
;; The Responses reasoning effort. These are reasoning models: some (e.g.
;; `gpt-6-astra`) only accept low/medium/high/xhigh/max (not "none"), so "low"
;; is the cheapest universally-accepted default. Any non-"none" effort streams
;; reasoning summaries and returns encrypted reasoning items that must be echoed
;; back into the next request — handled generically by the raw output-item echo
;; below (callers request `include: reasoning.encrypted_content`).
(def default-reasoning-effort "low")

(def ^:private default-gate
  (fn [tool-call] (js/Promise.resolve tool-call)))

;; ── History replay → Responses input items ────────────────────────────────

(defn history->responses-input
  "Convert the neutral message list from `xi.agent/history->messages` into
   OpenAI Responses `input` items so a sessionless turn replays prior context.
   One neutral message can expand into several items (an assistant turn becomes
   a `message` item plus one `function_call` item per tool call; a tool turn
   becomes one `function_call_output` per result)."
  [neutral]
  (vec
   (mapcat
    (fn [{:keys [role text tool-calls results]}]
      (case role
        :user
        [{:role "user" :content [{:type "input_text" :text text}]}]

        :assistant
        (concat
         (when (seq text)
           [{:type "message" :role "assistant"
             :content [{:type "output_text" :text text :annotations []}]
             :status "completed"}])
         ;; call_id only (omit the item id) so replayed calls don't trip the
         ;; reasoning/function_call pairing validation.
         (map (fn [{:keys [id name arguments]}]
                {:type "function_call"
                 :call_id id
                 :name name
                 :arguments (js/JSON.stringify (clj->js (or arguments {})))})
              tool-calls))

        :tool
        (map (fn [{:keys [id content is-error]}]
               {:type "function_call_output"
                :call_id id
                :output (str (when is-error "[error] ") content)})
             results)))
    neutral)))

;; ── Tool defs → Responses format ─────────────────────────────────────────────

(defn- xi-tool->responses
  [{:keys [name description input_schema]}]
  {:type "function"
   :name name
   :description description
   :parameters (or input_schema {:type "object" :properties {}})
   :strict false})

(defn build-tools []
  (mapv xi-tool->responses (tools/tool-definitions)))

;; ── Tool execution ───────────────────────────────────────────────────────────

(defn- execute-tool-call
  "Execute one function_call through Xi's registry + gate. Returns a promise of
   a Responses `function_call_output` item. Errors are surfaced in the output
   text (the Responses API has no error flag on tool output)."
  [{:keys [call_id name arguments]} registry tool-ctx tool-gate]
  (let [registry (or registry (tools/tool-registry))]
    (-> (tool-gate {:name name :arguments arguments})
        (.then
         (fn [gated]
  (cond
    (nil? gated)
    {:type "function_call_output" :call_id call_id
     :output "[error] Blocked by Xi permission gate"}

    (:intercepted gated)
    {:type "function_call_output" :call_id call_id
     :output (str (when (get-in gated [:result :is-error]) "[error] ")
                  (util/extract-text-content (get-in gated [:result :content])))}

    :else
    (let [exec-fn (get registry name)]
      (if exec-fn
        (-> (tools/run-tool exec-fn (or (:arguments gated) arguments) tool-ctx)
            (.then (fn [{:keys [content is-error]}]
                     {:type "function_call_output" :call_id call_id
                      :output (str (when is-error "[error] ")
                                   (util/extract-text-content content))})))
        {:type "function_call_output" :call_id call_id
         :output (str "[error] Unknown tool: " name)}))))))))

;; ── SSE parsing ──────────────────────────────────────────────────────────────

(defn- parse-sse-data [line]
  (when (str/starts-with? line "data: ")
    (let [data (subs line 6)]
      (when-not (= data "[DONE]")
        (try (js/JSON.parse data)
             (catch :default _ nil))))))

(defn- process-event!
  "Fold one Responses stream event into the per-request state atom + fire
   text/thinking callbacks. Completed output items are captured verbatim so the
   loop can echo them back into the next request's input."
  [^js ev state on-text on-thinking]
  (case (.-type ev)
    "response.output_text.delta"
    (when on-text (on-text (.-delta ev)))

    "response.reasoning_summary_text.delta"
    (when on-thinking (on-thinking (.-delta ev)))

    "response.output_item.done"
    (swap! state update :output-items conj
           (js->clj (.-item ev) :keywordize-keys true))

    ;; Codex emits `response.done`; the public Responses API emits
    ;; `response.completed`. Treat them identically.
    ("response.completed" "response.done")
    (let [^js resp (.-response ev)]
      (when-let [^js u (.-usage resp)]
        (let [cached (or (some-> (.-input_tokens_details u) .-cached_tokens) 0)]
          (swap! state assoc :usage
                 {:input_tokens (max 0 (- (or (.-input_tokens u) 0) cached))
                  :output_tokens (or (.-output_tokens u) 0)
                  :cache_read_input_tokens cached})))
      (swap! state assoc :status (.-status resp)))

    "response.failed"
    (let [^js err (some-> (.-response ev) .-error)]
      (swap! state assoc :error
             (if err
               (str (or (.-code err) "unknown") ": " (or (.-message err) "no message"))
               "response failed")))

    "response.incomplete"
    (swap! state assoc :status "incomplete")

    "error"
    (swap! state assoc :error
           (str "Error " (or (.-code ev) "") ": " (or (.-message ev) "unknown error")))

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

(defn- responses-tool-calls
  "Pull the function_call items out of the collected output items into
   {:call_id :name :arguments}."
  [output-items]
  (->> output-items
       (filter #(= "function_call" (:type %)))
       (mapv (fn [{:keys [call_id name arguments]}]
               {:call_id call_id
                :name name
                :arguments (try (js->clj (js/JSON.parse (or arguments "{}"))
                                         :keywordize-keys true)
                                (catch :default _ {}))}))))

(defn- stream-once
  "One Responses request. `build-request` owns the endpoint/headers/body.
   Returns a promise of {:text :output-items :tool-calls :usage :status}."
  [build-request provider-label
   {:keys [model input system responses-tools reasoning-effort
           on-text on-thinking abort-signal]}]
  (let [!text (atom "")
        state (atom {:output-items [] :usage nil :status nil :error nil})
        on-text* (fn [t] (swap! !text str t) (when on-text (on-text t)))
        effort (or reasoning-effort default-reasoning-effort)]
    (-> (build-request {:model model
                        :input input
                        :system system
                        :tools responses-tools
                        :reasoning-effort effort})
        (.then
         (fn [{:keys [url headers body]}]
           (js/fetch url
                     #js {:method "POST"
                          :headers (clj->js headers)
                          :body (js/JSON.stringify (clj->js body))
                          :signal abort-signal})))
        (.then
         (fn [^js response]
           (if (.-ok response)
             (read-sse-stream (.-body response) state on-text* on-thinking)
             (-> (.text response)
                 (.then (fn [body-text]
                          (throw (js/Error.
                                  (str provider-label " API error: "
                                       (.-status response) " " (.-statusText response)
                                       (when (and body-text (pos? (count body-text)))
                                         (str " — " body-text)))))))))))
        (.then
         (fn [final]
           (when-let [e (:error final)]
             (throw (js/Error. e)))
           {:text @!text
            :output-items (:output-items final)
            :tool-calls (responses-tool-calls (:output-items final))
            :usage (:usage final)
            :status (:status final)}))
        (.catch
         (fn [err]
           (throw (js/Error. (str provider-label " request failed: " (.-message err)))))))))

;; ── Turn loop ────────────────────────────────────────────────────────────────

(defn stream-messages
  "Run a full agent turn against an OpenAI Responses surface.

   `config` = {:build-request fn :provider-label str}. `build-request` is an
   async fn `(fn [{:keys [model input system tools reasoning-effort]}]
   → promise of {:url :headers :body}]` supplying the provider-specific request.

   Returns {:promise :abort!}."
  [{:keys [build-request provider-label]} opts]
  (let [callbacks (select-keys opts [:on-text :on-thinking :on-tool-start
                                     :on-tool-args :on-tool-result :on-error])
        cwd (or (:cwd opts) (.cwd js/process))
        model (:model opts)
        tool-gate (or (:tool-gate opts) default-gate)
        responses-tools (build-tools)

        system-text (let [tool-defs (tools/tool-definitions)
                          base (system-prompt/build tool-defs cwd)]
                      (if (:system opts)
                        (str base "\n\n" (:system opts))
                        base))

        abort-controller (js/AbortController.)
        flags #js {:aborted false}

        ;; Sessionless surface — replay the room's prior history ahead of the
        ;; current prompt so the model keeps context across turns.
        prior-input (some-> (:history opts)
                            agent/history->messages
                            history->responses-input)
        !input (atom (conj (vec prior-input)
                           {:role "user"
                            :content [{:type "input_text" :text (:prompt opts)}]}))
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
                       (-> (stream-once build-request provider-label
                                        {:model model
                                         :input @!input
                                         :system system-text
                                         :responses-tools responses-tools
                                         :reasoning-effort (:reasoning-effort opts)
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
                                           (update :cache_read_input_tokens (fnil + 0) (:cache_read_input_tokens u 0))))))
                     ;; Echo the model's own output items back into the input so
                     ;; the next request keeps message/reasoning/function_call
                     ;; pairing and ids intact.
                     (swap! !input into (:output-items result))
                     (if-not (seq (:tool-calls result))
                       ;; No tool calls. If the model was cut off mid-thought
                       ;; (incomplete → truncated), re-request to continue rather
                       ;; than silently ending the turn.
                       (if (and (= "incomplete" (:status result))
                                (seq (:text result))
                                (< (inc n) max-iterations))
                         (iterate-turn (inc n))
                         (finish {:content [{:type "text" :text (:text result)}]
                                  :stop-reason "stop"
                                  :result-text (:text result)}))
                       (let [tcs (:tool-calls result)]
                         (doseq [tc tcs]
                           (when (:on-tool-start callbacks)
                             ((:on-tool-start callbacks)
                              {:id (:call_id tc) :name (:name tc) :arguments (:arguments tc)}))
                           (when (:on-tool-args callbacks)
                             ((:on-tool-args callbacks)
                              {:id (:call_id tc) :name (:name tc) :arguments (:arguments tc)})))
                         (-> (js/Promise.all
                              (clj->js (mapv #(execute-tool-call % (tools/with-extensions (:extra-tool-registry opts)) (assoc (:tool-ctx opts) :cwd cwd) tool-gate) tcs)))
                             (.then
                              (fn [results]
                                (let [outputs (js->clj results :keywordize-keys true)]
                                  (doseq [[tc out] (map vector tcs outputs)]
                                    (when (:on-tool-result callbacks)
                                      ((:on-tool-result callbacks)
                                       {:id (:call_id tc)
                                        :content (:output out)
                                        :is-error (str/starts-with? (str (:output out)) "[error] ")})))
                                  (swap! !input into outputs)
                                  (iterate-turn (inc n)))))))))]
             (iterate-turn 0))))]

    {:promise promise
     :abort!  (fn []
                (set! (.-aborted flags) true)
                (.abort abort-controller))}))
