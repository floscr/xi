(ns xi.loop
  "Agent loop — delegates to Claude CLI bridge for full turn execution.
   The CLI handles tool execution, multi-turn, and the agent loop internally.
   Xi streams events and records them."
  (:require [clojure.string :as str]
            [xi.provider :as provider]))

(defn run-turn
  "Run one full agent turn via the Claude CLI bridge.
   Returns promise of {:messages [...] :usage {...} :session-id ...}.

   The CLI runs its own agent loop — Xi doesn't execute tools directly.
   Xi streams the events for display and records the result.

   opts:
     :model       - model id
     :prompt      - user prompt string
     :system      - extra system prompt to append
     :on-text     - callback for text deltas
     :on-thinking - callback for thinking deltas
     :on-tool-start - callback when CLI starts a tool
     :on-tool-result - callback when CLI gets a tool result
     :on-error    - callback for errors"
  [opts]
  (-> (provider/stream-messages
       {:model (:model opts)
        :prompt (:prompt opts)
        :system (:system opts)
        :on-text (:on-text opts)
        :on-thinking (:on-thinking opts)
        :on-tool-start (:on-tool-start opts)
        :on-tool-result (:on-tool-result opts)
        :on-error (:on-error opts)})
      (.then
       (fn [state]
         (let [assistant-msg (provider/response->assistant-message state)]
           {:messages [assistant-msg]
            :usage (:usage state)
            :session-id (:session-id state)
            :result-text (:result-text state)
            :cost (:cost state)})))))
