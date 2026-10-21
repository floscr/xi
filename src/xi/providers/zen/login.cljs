(ns xi.providers.zen.login
  "Sign-in to the OpenCode Console for the Go subscription: the device flow
   (RFC 8628) the OpenCode CLI runs, as its client `opencode-cli`, against
   https://opencode.ai/console. The tokens live at ~/.config/xi/opencode-go.json
   (`{\"access\" \"refresh\" \"expires\" \"org_id\" \"email\"}`, mode 0600)
   and are refreshed on use (xi.providers.zen.auth); a refresh the Console
   answers with `invalid_grant` deletes them — only a new sign-in recovers.

   Interface: (login! {:on-prompt f}) → Promise<credentials>,
   (access-token) → Promise<credentials|nil>, (logout!)."
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]))

(def console-url "https://opencode.ai/console")
(def ^:private client-id "opencode-cli")
;; Refresh this long before the Console's expiry so a turn never starts on a
;; token about to lapse mid-way.
(def ^:private expiry-skew-ms (* 60 1000))
(def ^:private request-timeout-ms 15000)

;; ── Credential file ──────────────────────────────────────────────────────────

(defn credentials-path []
  (path/join (os/homedir) ".config" "xi" "opencode-go.json"))

(defn read-credentials
  "The stored sign-in {:access :refresh :expires :org-id :email}, or nil."
  []
  (try
    (let [p (credentials-path)]
      (when (.existsSync fs p)
        (let [^js j (js/JSON.parse (.readFileSync fs p "utf8"))]
          (when (string? (.-access j))
            (cond-> {:access (.-access j) :refresh (.-refresh j) :expires (.-expires j)}
              (.-org_id j) (assoc :org-id (.-org_id j))
              (.-email j)  (assoc :email (.-email j)))))))
    (catch :default _ nil)))

(defn write-credentials! [{:keys [access refresh expires org-id email]}]
  (let [p   (credentials-path)
        tmp (str p ".tmp")]
    (.mkdirSync fs (path/dirname p) #js {:recursive true})
    (.writeFileSync fs tmp
                    (js/JSON.stringify (clj->js (cond-> {:access access :refresh refresh :expires expires}
                                                  org-id (assoc :org_id org-id)
                                                  email  (assoc :email email)))
                                       nil 2)
                    #js {:mode 384})
    (.renameSync fs tmp p)))

(defn delete-credentials! []
  (try (.rmSync fs (credentials-path) #js {:force true})
       (catch :default _ nil)))

;; ── Pure helpers ─────────────────────────────────────────────────────────────

(defn expired?
  "True when the access token has lapsed at `now`, or does within the skew."
  [{:keys [expires]} now]
  (or (not (number? expires))
      (>= now (- expires expiry-skew-ms))))

(defn credentials-from-token
  "Credentials from a Console token response (keywordized). A refresh may
   omit the refresh token, org and email: those stay from `prev`."
  [{:keys [access_token refresh_token expires_in org_id]} now prev]
  (when-not (string? access_token)
    (throw (js/Error. "OpenCode token response has no access_token")))
  (let [org (or org_id (:org-id prev))]
    (cond-> {:access  access_token
             :refresh (or refresh_token (:refresh prev))
             :expires (+ now (* 1000 (if (number? expires_in) expires_in 0)))}
      org           (assoc :org-id org)
      (:email prev) (assoc :email (:email prev)))))

(defn verification-url
  "The page the user opens, resolved against the Console the way the OpenCode
   CLI does (the Console may answer a path or an absolute URL)."
  [{:keys [verification_uri_complete verification_uri]}]
  (str (js/URL. (or verification_uri_complete verification_uri) (str console-url "/"))))

;; ── Console requests ─────────────────────────────────────────────────────────

(defn- post-json
  "POST `body` as JSON → Promise<{:status :ok? :body}>, the body parsed when
   it is JSON (else {:raw text})."
  [url body]
  (-> (js/fetch url #js {:method  "POST"
                         :headers #js {"Content-Type" "application/json"
                                       "Accept" "application/json"}
                         :body    (js/JSON.stringify (clj->js body))
                         :signal  (js/AbortSignal.timeout request-timeout-ms)})
      (.then (fn [^js res]
               (-> (.text res)
                   (.then (fn [t]
                            {:status (.-status res)
                             :ok?    (.-ok res)
                             :body   (try (js->clj (js/JSON.parse t) :keywordize-keys true)
                                          (catch :default _ {:raw t}))})))))))

(defn- fail-text [{:keys [status body]}]
  (str status " " (or (:error_description body) (:error body) (:message body) (:raw body) "")))

(defn- sleep [ms]
  (js/Promise. (fn [resolve _] (js/setTimeout resolve ms))))

(defn start-device-flow!
  "→ Promise<{:device-code :user-code :url :interval-ms :expires-at}>."
  []
  (-> (post-json (str console-url "/auth/device/code")
                 {:client_id client-id :supports_org_scope true})
      (.then (fn [{:keys [ok? body] :as res}]
               (when-not (and ok? (:device_code body) (:user_code body))
                 (throw (js/Error. (str "OpenCode device code request failed: " (fail-text res)))))
               {:device-code (:device_code body)
                :user-code   (:user_code body)
                :url         (verification-url body)
                :interval-ms (* 1000 (or (:interval body) 5))
                :expires-at  (+ (js/Date.now) (* 1000 (or (:expires_in body) 900)))}))))

(defn poll-token!
  "Poll the token endpoint until the user approves → Promise<credentials>.
   Rejects when the code expires or the user denies."
  [{:keys [device-code interval-ms expires-at]}]
  (letfn [(attempt [wait]
            (-> (sleep wait)
                (.then (fn []
                         (when (> (js/Date.now) expires-at)
                           (throw (js/Error. "the sign-in code expired before it was approved")))
                         (post-json (str console-url "/auth/device/token")
                                    {:grant_type  "urn:ietf:params:oauth:grant-type:device_code"
                                     :device_code device-code
                                     :client_id   client-id})))
                (.then (fn [{:keys [ok? body] :as res}]
                         (case (when-not (and ok? (:access_token body)) (:error body))
                           nil                     (credentials-from-token body (js/Date.now) nil)
                           "authorization_pending" (attempt wait)
                           "slow_down"             (attempt (+ wait 5000))
                           "access_denied"         (throw (js/Error. "the sign-in was denied"))
                           "expired_token"         (throw (js/Error. "the sign-in code expired before it was approved"))
                           (throw (js/Error. (str "OpenCode sign-in failed: " (fail-text res)))))))))]
    (attempt interval-ms)))

(defn- fetch-email
  "Best-effort: the account's email from the Console; nil on any failure."
  [access]
  (-> (js/fetch (str console-url "/api/user")
                #js {:headers #js {"Authorization" (str "Bearer " access)
                                   "Accept" "application/json"}
                     :signal  (js/AbortSignal.timeout request-timeout-ms)})
      (.then (fn [^js res] (when (.-ok res) (.json res))))
      (.then (fn [^js j] (when j (.-email j))))
      (.catch (fn [_] nil))))

(defn login!
  "Run the device flow: `on-prompt` gets {:url :user-code} to show the user.
   Resolves to the stored credentials once the Console approves."
  [{:keys [on-prompt]}]
  (-> (start-device-flow!)
      (.then (fn [device]
               (when on-prompt (on-prompt (select-keys device [:url :user-code])))
               (poll-token! device)))
      (.then (fn [creds]
               (-> (fetch-email (:access creds))
                   (.then (fn [email]
                            (let [creds (cond-> creds email (assoc :email email))]
                              (write-credentials! creds)
                              creds))))))))

;; The Console rotates refresh tokens, so two rooms refreshing at once would
;; sign the device out: one refresh in flight, shared by every caller.
(defonce ^:private !refreshing (atom nil))

(defn- refresh-once! [creds]
  (-> (post-json (str console-url "/auth/device/token")
                 {:grant_type    "refresh_token"
                  :refresh_token (:refresh creds)
                  :client_id     client-id})
      (.then (fn [{:keys [ok? body status] :as res}]
               (cond
                 (and ok? (:access_token body))
                 (let [next (credentials-from-token body (js/Date.now) creds)]
                   (write-credentials! next)
                   next)

                 (and (= 400 status) (= "invalid_grant" (:error body)))
                 (do (delete-credentials!)
                     (throw (js/Error. "OpenCode signed this device out; sign in again with /login opencode")))

                 :else
                 (throw (js/Error. (str "OpenCode token refresh failed: " (fail-text res)))))))))

(defn refresh!
  "Exchange the refresh token → Promise<credentials> (stored)."
  [creds]
  (or @!refreshing
      (let [p (-> (refresh-once! creds)
                  (.finally (fn [] (reset! !refreshing nil))))]
        (reset! !refreshing p)
        p)))

(defn access-token
  "→ Promise<credentials|nil>: the stored sign-in, refreshed when about to
   expire; nil when not signed in. Read fresh each call so a sign-in
   mid-session is picked up."
  []
  (if-let [creds (read-credentials)]
    (if (and (expired? creds (js/Date.now)) (:refresh creds))
      (refresh! creds)
      (js/Promise.resolve creds))
    (js/Promise.resolve nil)))

(defn logout! []
  (delete-credentials!))
