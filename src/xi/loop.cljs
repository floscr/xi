(ns xi.loop
  "Agent loop — routes to the correct provider based on model.
   Claude models → SDK bridge (provider.cljs)
   Everything else → raw OpenAI-compatible API (provider/ollama.cljs)"
  (:require [clojure.string :as str]
            [xi.provider :as bridge]
            [xi.provider.ollama :as ollama]))

(defn claude-model?
  "Returns true if model string looks like a Claude/Anthropic model."
  [model]
  (when model
    (or (str/starts-with? model "claude-")
        (str/starts-with? model "anthropic/")
        ;; Bare aliases used by the SDK
        (contains? #{"sonnet" "opus" "haiku"} model))))

(defn run-turn
  "Run one full agent turn. Routes to the right provider.
   Returns promise of {:messages [...] :usage {...} :session-id ...}.

   opts:
     :model       - model id
     :prompt      - user prompt string
     :system      - extra system prompt to append
     :effort      - reasoning effort level (low/medium/high/xhigh/max)
     :on-text     - callback for text deltas
     :on-thinking - callback for thinking deltas
     :on-tool-start - callback when a tool call starts
     :on-tool-args  - callback with parsed tool arguments
     :on-tool-result - callback when a tool returns
     :on-error    - callback for errors"
  [opts]
  (if (claude-model? (:model opts))
    ;; Claude models → SDK bridge
    (-> (bridge/stream-messages
         {:model (:model opts)
          :prompt (:prompt opts)
          :cwd (:cwd opts)
          :system (:system opts)
          :effort (:effort opts)
          :resume-session-id (:resume-session-id opts)
          :abort-signal (:abort-signal opts)
          :on-text (:on-text opts)
          :on-thinking (:on-thinking opts)
          :on-tool-start (:on-tool-start opts)
          :on-tool-args (:on-tool-args opts)
          :on-tool-result (:on-tool-result opts)
          :on-error (:on-error opts)})
        (.then
         (fn [state]
           (let [assistant-msg (bridge/response->assistant-message state)]
             {:messages [assistant-msg]
              :usage (:usage state)
              :session-id (:session-id state)
              :result-text (:result-text state)
              :cost (:cost state)}))))

    ;; Everything else → raw OpenAI-compatible provider
    (-> (ollama/stream-messages
         {:model (:model opts)
          :prompt (:prompt opts)
          :cwd (:cwd opts)
          :system (:system opts)
          :abort-signal (:abort-signal opts)
          :on-text (:on-text opts)
          :on-thinking (:on-thinking opts)
          :on-tool-start (:on-tool-start opts)
          :on-tool-args (:on-tool-args opts)
          :on-tool-result (:on-tool-result opts)
          :on-error (:on-error opts)})
        (.then
         (fn [state]
           (let [assistant-msg (ollama/response->assistant-message state)]
             {:messages [assistant-msg]
              :usage (:usage state)
              :result-text (:result-text state)
              :cost nil}))))))
