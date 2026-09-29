(ns xi.providers.openai.auth
  "Reuse the Codex CLI's ChatGPT-subscription credentials.

   Codex (the `codex` CLI) stores its OAuth tokens at ~/.codex/auth.json:

     {\"OPENAI_API_KEY\": null,
      \"tokens\": {\"id_token\": \"…\", \"access_token\": \"eyJ…\",
                   \"refresh_token\": \"…\", \"account_id\": \"…\"},
      \"last_refresh\": \"2025-01-01T00:00:00Z\"}

   Xi reuses these directly — analogous to how the Claude provider reuses
   ~/.pi/agent/auth.json — so `codex login` is the only auth step. We decode the
   `access_token` JWT to check expiry (and to recover the `chatgpt_account_id`
   header value), refresh against https://auth.openai.com/oauth/token when
   expired, and write the fresh tokens back so the codex CLI stays in sync.

   `resolve-token` returns a promise of {:access-token :account-id} (or nil when
   no credentials are present).

   Override the file location with CODEX_HOME (matches the codex CLI)."
  (:require [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]))

(def ^:private client-id "app_EMoamEEZ73f0CkXaXp7hrann")
(def ^:private token-url "https://auth.openai.com/oauth/token")
(def ^:private jwt-claim "https://api.openai.com/auth")
;; Refresh a bit before the token's exp so an in-flight turn doesn't 401.
(def ^:private expiry-skew-ms (* 60 1000))

(defn- env [k]
  (let [v (aget js/process.env k)]
    (when (and (string? v) (pos? (count v))) v)))

(defn auth-path []
  (path/join (or (env "CODEX_HOME") (path/join (os/homedir) ".codex"))
             "auth.json"))

(defn- decode-jwt
  "Decode a JWT payload into a clj map, or nil."
  [token]
  (try
    (let [parts (str/split token #"\.")]
      (when (= 3 (count parts))
        (let [payload (js/Buffer.from (nth parts 1) "base64")]
          (js->clj (js/JSON.parse (.toString payload "utf8"))
                   :keywordize-keys true))))
    (catch :default _ nil)))

(defn- account-id-from-token
  "Pull chatgpt_account_id out of the access-token JWT claim."
  [access-token]
  (get-in (decode-jwt access-token) [(keyword jwt-claim) :chatgpt_account_id]))

(defn- token-expired?
  "True when the access token is missing an exp, or is at/near expiry."
  [access-token]
  (let [exp (:exp (decode-jwt access-token))]
    (or (not (number? exp))
        (>= (js/Date.now) (- (* exp 1000) expiry-skew-ms)))))

(defn- read-auth
  "Read + parse ~/.codex/auth.json into a JS object, or nil when absent."
  []
  (try
    (let [p (auth-path)]
      (when (.existsSync fs p)
        (js/JSON.parse (.readFileSync fs p "utf8"))))
    (catch :default _ nil)))

(defn- write-tokens!
  "Persist refreshed tokens back into ~/.codex/auth.json, preserving the rest of
   the file so the codex CLI keeps working."
  [^js raw {:keys [id-token access-token refresh-token]}]
  (try
    (let [tokens (or (.-tokens raw) #js {})]
      (when id-token (set! (.-id_token tokens) id-token))
      (when access-token (set! (.-access_token tokens) access-token))
      (when refresh-token (set! (.-refresh_token tokens) refresh-token))
      (set! (.-tokens raw) tokens)
      (set! (.-last_refresh raw) (.toISOString (js/Date.)))
      (.writeFileSync fs (auth-path) (js/JSON.stringify raw nil 2) "utf8"))
    (catch :default _ nil)))

(defn- refresh!
  "Exchange the refresh token for a new access token. Returns a promise of
   {:access-token :account-id} or rejects."
  [^js raw refresh-token]
  (-> (js/fetch token-url
                #js {:method "POST"
                     :headers #js {"Content-Type" "application/json"}
                     :body (js/JSON.stringify
                            #js {:grant_type "refresh_token"
                                 :refresh_token refresh-token
                                 :client_id client-id
                                 :scope "openid profile email"})})
      (.then (fn [^js resp]
               (if (.-ok resp)
                 (.json resp)
                 (-> (.text resp)
                     (.then (fn [t]
                              (throw (js/Error. (str "Codex token refresh failed: "
                                                     (.-status resp) " " t)))))))))
      (.then (fn [^js json]
               (let [access (.-access_token json)
                     id-tok (.-id_token json)
                     ;; OpenAI may or may not rotate the refresh token.
                     new-refresh (or (.-refresh_token json) refresh-token)]
                 (when-not access
                   (throw (js/Error. "Codex token refresh returned no access_token")))
                 (write-tokens! raw {:id-token id-tok
                                     :access-token access
                                     :refresh-token new-refresh})
                 {:access-token access
                  :account-id (account-id-from-token access)})))))

(defn resolve-token
  "Resolve a valid Codex access token. Reads ~/.codex/auth.json fresh each call
   (so a `codex login` mid-session is picked up), refreshing when the access
   token is expired. Returns a promise of {:access-token :account-id}, or nil
   when no credentials are configured."
  []
  (let [raw (read-auth)]
    (when raw
      (let [tokens (.-tokens raw)
            access (some-> tokens .-access_token)
            refresh (some-> tokens .-refresh_token)
            account (or (some-> tokens .-account_id)
                        (some-> access account-id-from-token))]
        (cond
          (and access (not (token-expired? access)))
          (js/Promise.resolve {:access-token access :account-id account})

          refresh
          (refresh! raw refresh)

          access
          (js/Promise.resolve {:access-token access :account-id account})

          :else nil)))))
