(ns xi.provider.zen
  "OpenCode Zen provider — a curated multi-model AI gateway.

   Zen serves models across several wire formats behind one gateway
   (https://opencode.ai/zen/v1). This provider dispatches each turn to the
   right adapter based on the model's wire-format (see xi.provider.zen.models):

     :chat      OpenAI Chat Completions  — via xi.provider.openai-compat
                (DeepSeek, GLM, Kimi, MiniMax, big-pickle, all *-free models)
     :messages  Anthropic Messages       — via xi.provider.zen.anthropic
                (Claude, Qwen)
     :responses OpenAI Responses         — not yet implemented (GPT, Grok, Muse)
     :gemini    Google generateContent   — not yet implemented (Gemini)

   Auth: OPENCODE_API_KEY / OPENCODE_ZEN_API_KEY, else OpenCode's own
   ~/.local/share/opencode/auth.json (see xi.provider.zen.auth). The free
   chat-completions models work without a key.

   Interface: (stream-messages opts) → {:promise :abort!}."
  (:require [xi.provider.openai-compat :as oai]
            [xi.provider.zen.anthropic :as anthropic]
            [xi.provider.zen.auth :as auth]
            [xi.provider.zen.models :as models]))

(defn- unsupported
  "Return a turn handle that reports an unsupported wire format instead of
   crashing — the :responses (GPT) and :gemini surfaces land in a follow-up."
  [wire opts]
  (let [msg (str "OpenCode Zen model uses the " (name wire)
                 " API surface, which Xi doesn't support yet. "
                 "Supported now: chat-completions (DeepSeek, GLM, Kimi, "
                 "MiniMax, big-pickle, *-free) and Anthropic (Claude, Qwen).")]
    {:promise (js/Promise.resolve
               (do (when-let [f (:on-error opts)]
                     (f {:type "error" :message msg}))
                   {:content [] :stop-reason "error"
                    :usage {:input_tokens 0 :output_tokens 0}
                    :model (:model opts) :done true}))
     :abort!  (fn [] nil)}))

(defn- bearer-headers []
  (if-let [k (auth/api-key)]
    {"Authorization" (str "Bearer " k)}
    {}))

(defn stream-messages
  "Run one Zen turn, dispatching to the adapter for the model's wire format."
  [opts]
  (let [wire (models/wire-format (:model opts))
        ;; Send the bare model id (drop any `opencode/` prefix) to the API.
        opts (assoc opts :model (models/strip-prefix (:model opts)))]
    (case wire
      :chat
      (oai/stream-messages
       {:base-url  models/base-url
        :chat-path "/chat/completions"
        :headers   (bearer-headers)
        :label     "Zen"}
       opts)

      :messages
      (anthropic/stream-messages
       {:api-key  (auth/api-key)
        :base-url models/base-url}
       opts)

      ;; :responses / :gemini — follow-up
      (unsupported wire opts))))

(def provider
  {:id :zen
   :start-turn! stream-messages})
