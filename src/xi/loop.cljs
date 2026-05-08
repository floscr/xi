(ns xi.loop
  "Agent loop — delegates to Claude Agent SDK for turn orchestration.
   CC proposes tool calls via MCP; Xi intercepts and executes them
   through its own tool pipeline (with permission gate hooks)."
  (:require [xi.provider :as provider]))

(defn run-turn
  "Run one full agent turn via the SDK with MCP tool bridge.
   Returns promise of {:messages [...] :usage {...} :session-id ...}.

   CC proposes tool calls, Xi executes them through its tool registry
   (with extension hooks for permission gating).

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
