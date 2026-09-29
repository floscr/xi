(ns xi.providers.zen.responses
  "OpenCode Zen OpenAI-Responses surface (GPT, Grok, Muse models).

   Raw HTTP to POST https://opencode.ai/zen/v1/responses — the OpenAI Responses
   API streaming SSE wire format, driven by the shared adapter in
   xi.provider.openai.responses (which owns the SSE parsing + tool-use loop).
   This namespace only supplies the Zen-specific request: a bearer token, the
   `/responses` endpoint, and a body that carries the system prompt as a
   `developer`-role input item.

   The turn runs stateless (`store:false`) — the full input is re-sent each
   iteration — so it needs no server-side conversation state.

   Interface: (stream-messages config opts) → {:promise :abort!}."
  (:require [xi.providers.openai.responses :as responses]))

(def ^:private max-output-tokens 32000)

(defn- build-request
  "Build a Zen `/responses` request. The system prompt is sent as a
   `developer`-role item prepended to the input."
  [{:keys [api-key base-url]}
   {:keys [model input system tools reasoning-effort]}]
  (let [input* (into (if system
                       [{:role "developer" :content system}]
                       [])
                     input)
        body (cond-> {:model model
                      :input input*
                      :stream true
                      :store false
                      :max_output_tokens max-output-tokens
                      :reasoning {:effort reasoning-effort}}
               (not= reasoning-effort "none") (assoc :include ["reasoning.encrypted_content"])
               (seq tools) (assoc :tools tools))]
    (js/Promise.resolve
     {:url (str base-url "/responses")
      :headers {"Content-Type" "application/json"
                "Authorization" (str "Bearer " api-key)}
      :body body})))

(defn stream-messages
  "Run a full agent turn against the Zen OpenAI-Responses surface.
   `config` = {:api-key :base-url}. Returns {:promise :abort!}."
  [config opts]
  (responses/stream-messages
   {:provider-label "Zen (responses)"
    :build-request (partial build-request config)}
   opts))
