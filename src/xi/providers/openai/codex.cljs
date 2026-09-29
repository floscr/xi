(ns xi.providers.openai.codex
  "OpenAI ChatGPT-subscription (Codex) provider.

   Talks to the same Codex Responses backend the `codex` CLI uses —
   POST https://chatgpt.com/backend-api/codex/responses — reusing the CLI's
   OAuth credentials from ~/.codex/auth.json (see xi.providers.openai.auth), so
   real GPT/Codex models (gpt-5.1-codex, gpt-5-codex, …) run against the user's
   ChatGPT plan rather than a pay-per-token API key.

   The wire format is the OpenAI Responses SSE protocol, so the SSE parsing +
   tool-use loop are the shared ones in xi.providers.openai.responses. This
   namespace supplies the Codex-specific request: the endpoint, ChatGPT auth
   headers (bearer token + `chatgpt-account-id`), and a body that carries the
   system prompt in the top-level `instructions` field.

   Select a model with the `openai/` prefix, e.g. `/model openai/gpt-5.1-codex`.

   Interface: (stream-messages opts) → {:promise :abort!}."
  (:require [clojure.string :as str]
            [xi.providers.openai.auth :as auth]
            [xi.providers.openai.responses :as responses]))

(def ^:private codex-url "https://chatgpt.com/backend-api/codex/responses")
;; Identify as the codex CLI (the client these credentials belong to).
(def ^:private originator "codex_cli_rs")

(defn strip-prefix
  "Drop the leading `openai/` from a model id, if present."
  [model]
  (if (and model (str/starts-with? model "openai/"))
    (subs model (count "openai/"))
    model))

(defn- build-request
  "Build a Codex `/codex/responses` request. Resolves (and refreshes) the Codex
   access token first, then sends the system prompt in the `instructions`
   field with the ChatGPT auth headers."
  [{:keys [model input system tools reasoning-effort]}]
  (-> (or (auth/resolve-token)
          (js/Promise.reject
           (js/Error. (str "No Codex credentials found at " (auth/auth-path)
                           ". Run `codex login` first."))))
      (.then
       (fn [{:keys [access-token account-id]}]
         (when-not access-token
           (throw (js/Error. "Codex credentials are missing an access token")))
         (let [body (cond-> {:model model
                             :input input
                             :instructions system
                             :store false
                             :stream true
                             :include ["reasoning.encrypted_content"]
                             :tool_choice "auto"
                             :parallel_tool_calls true
                             :reasoning {:effort reasoning-effort :summary "auto"}
                             :text {:verbosity "medium"}}
                      (seq tools) (assoc :tools tools))]
           {:url codex-url
            :headers (cond-> {"Content-Type" "application/json"
                              "Accept" "text/event-stream"
                              "Authorization" (str "Bearer " access-token)
                              "OpenAI-Beta" "responses=experimental"
                              "originator" originator}
                       account-id (assoc "chatgpt-account-id" account-id))
            :body body})))))

(defn stream-messages
  "Run one Codex turn. Sends the bare model id (drops the `openai/` prefix)."
  [opts]
  (responses/stream-messages
   {:provider-label "OpenAI Codex"
    :build-request build-request}
   (assoc opts :model (strip-prefix (:model opts)))))

(def provider
  {:id :openai
   :start-turn! stream-messages})
