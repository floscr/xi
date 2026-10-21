(ns xi.providers.zen
  "OpenCode provider — the Zen pay-as-you-go gateway and the OpenCode Go
   subscription gateway.

   Both serve models across several wire formats behind one base URL each
   (see xi.providers.zen.models for the gateways and the table). This provider
   dispatches each turn to the adapter for the model's wire format:

     :chat      OpenAI Chat Completions  — via xi.providers.openai-compat
                (DeepSeek, GLM, Kimi, MiniMax, big-pickle, all *-free models)
     :messages  Anthropic Messages       — via xi.providers.zen.anthropic
                (Claude, Qwen)
     :responses OpenAI Responses         — via xi.providers.zen.responses
                (GPT, Grok, Muse)
     :gemini    Google generateContent   — not yet implemented (Gemini)

   Auth per gateway: xi.providers.zen.auth — Go prefers the Console sign-in
   (xi.providers.zen.login; `/login opencode`, `xi login opencode`, both
   through this provider's :login map). The free chat-completions models work
   without a credential.

   Every request identifies Xi in `User-Agent` and carries the room id as
   `x-opencode-session`: Go asks both of coding agents and uses the session
   for routing and prompt caching.

   Interface: (stream-messages opts) → {:promise :abort!}."
  (:require [xi.providers.openai-compat :as oai]
            [xi.providers.zen.anthropic :as anthropic]
            [xi.providers.zen.auth :as auth]
            [xi.providers.zen.login :as login]
            [xi.providers.zen.models :as models]
            [xi.providers.zen.responses :as responses]))

;; Go rejects generic SDK / HTTP-library user agents.
(def ^:private user-agent "xi-agent")

(def ^:private not-signed-in
  "OpenCode Go needs a sign-in: run /login opencode (or `xi login opencode`), or set OPENCODE_GO_API_KEY.")

(defn- unsupported-message [wire]
  (str "OpenCode model uses the " (name wire)
       " API surface, which Xi doesn't support yet. "
       "Supported now: chat-completions (DeepSeek, GLM, Kimi, "
       "MiniMax, big-pickle, *-free), Anthropic (Claude, Qwen), "
       "and OpenAI Responses (GPT, Grok, Muse)."))

(defn- failed
  "The result of a turn that never reached the model: `msg` goes to :on-error
   and the turn ends with stop-reason \"error\"."
  [msg opts]
  (when-let [f (:on-error opts)]
    (f {:type "error" :message msg}))
  {:content [] :stop-reason "error"
   :usage {:input_tokens 0 :output_tokens 0}
   :model (:model opts) :done true})

(defn- aborted [opts]
  {:content [] :stop-reason "aborted"
   :usage {:input_tokens 0 :output_tokens 0}
   :model (:model opts) :aborted true :done true})

(defn client-headers
  "`User-Agent`, `x-opencode-session` (the room id) and, for a sign-in scoped
   to a Console organization, `x-org-id`."
  [opts credential]
  (cond-> {"User-Agent" user-agent}
    (:room-id opts)      (assoc "x-opencode-session" (str (:room-id opts)))
    (:org-id credential) (assoc "x-org-id" (:org-id credential))))

(defn- dispatch-turn
  "Start the adapter for `wire` on `gateway` with `credential`
   ({:token :org-id} or nil) → {:promise :abort!}."
  [gateway wire credential opts]
  (let [token   (:token credential)
        headers (client-headers opts credential)
        bearer  (when token {"Authorization" (str "Bearer " token)})
        base    (models/base-url gateway wire)]
    (case wire
      :chat
      (oai/stream-messages
       {:base-url  base
        :chat-path "/chat/completions"
        :headers   (merge headers bearer)
        :label     (get models/labels gateway)}
       opts)

      :messages
      (anthropic/stream-messages
       {:api-key  token
        :base-url base
        ;; The adapter sends the token as x-api-key; a sign-in token is
        ;; also accepted as a bearer, so send both.
        :headers  (merge headers bearer)}
       opts)

      :responses
      (responses/stream-messages
       {:api-key  token
        :base-url base
        :headers  headers}
       opts)

      ;; :gemini — follow-up
      {:promise (js/Promise.resolve (failed (unsupported-message wire) opts))
       :abort!  (fn [] nil)})))

(defn stream-messages
  "Run one OpenCode turn: resolve the gateway's credential (refreshing a
   sign-in), then dispatch to the adapter for the model's wire format."
  [opts]
  (let [model    (:model opts)
        gateway  (models/gateway model)
        wire     (models/wire-format model)
        ;; Send the bare model id (drop the gateway prefix) to the API.
        opts     (assoc opts :model (models/strip-prefix model))
        !inner   (atom nil)
        !aborted (atom false)
        promise  (-> (auth/credential gateway)
                     (.then (fn [credential]
                              (cond
                                @!aborted
                                (aborted opts)

                                (and (= :go gateway) (nil? credential))
                                (failed not-signed-in opts)

                                :else
                                (let [h (dispatch-turn gateway wire credential opts)]
                                  (reset! !inner h)
                                  (:promise h)))))
                     (.catch (fn [err]
                               (failed (str (get models/labels gateway) ": " (.-message err)) opts))))]
    {:promise promise
     :abort!  (fn []
                (reset! !aborted true)
                (when-let [h @!inner] ((:abort! h))))}))

(defn- list-gateway-models!
  "Promise of `gateway`'s model ids, each with the gateway's prefix so the
   picker routes them here. Empty on error (best-effort)."
  [gateway]
  (-> (js/fetch (models/models-url gateway)
                #js {:signal (js/AbortSignal.timeout 4000)})
      (.then (fn [^js res] (.json res)))
      (.then (fn [^js data]
               (->> (js->clj (.-data data) :keywordize-keys true)
                    (keep :id)
                    (mapv (fn [id] (str (models/prefix gateway) id))))))
      (.catch (fn [_err] []))))

(defn list-models!
  "Promise of the Zen ids (`opencode/…`) followed by the Go ids
   (`opencode-go/…`)."
  []
  (-> (js/Promise.all #js [(list-gateway-models! :zen)
                           (list-gateway-models! :go)])
      (.then (fn [[zen go]] (into zen go)))))

(def provider
  ;; Sessionless: xi.cli records its transcripts (xi.session.recorder).
  {:id :zen
   :start-turn! stream-messages
   :list-models! list-models!
   ;; `/login opencode` (xi.fx) and `xi login opencode` (xi.cli).
   :login {:name    "opencode"
           :label   "OpenCode Go"
           :login!  login/login!
           :logout! login/logout!}})
