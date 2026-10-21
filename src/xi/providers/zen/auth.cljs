(ns xi.providers.zen.auth
  "Resolve the OpenCode credential for a gateway (see xi.providers.zen.models).

   Zen (pay-as-you-go) is key-only, in order:
     1. OPENCODE_API_KEY env var
     2. OPENCODE_ZEN_API_KEY env var
     3. OpenCode's own credential store at
        ~/.local/share/opencode/auth.json → {\"opencode\":{\"type\":\"api\",\"key\":\"sk-…\"}}

   OpenCode Go (subscription) prefers the Console sign-in
   (xi.providers.zen.login, `/login opencode`), refreshed here when it is
   about to expire; without one: OPENCODE_GO_API_KEY, the `opencode-go` entry
   of auth.json, then the Zen resolution above.

   A nil credential is fine for the free chat-completions models (they don't
   require auth); paid models and the Anthropic/Responses surfaces need one."
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [xi.providers.zen.login :as login]))

(defn- env [k]
  (let [v (aget js/process.env k)]
    (when (and (string? v) (pos? (count v))) v)))

(defn- opencode-auth-path []
  (path/join (os/homedir) ".local" "share" "opencode" "auth.json"))

(defn- key-from-file
  "The `key` of `entry` (\"opencode\" or \"opencode-go\") in auth.json."
  [entry]
  (try
    (let [p (opencode-auth-path)]
      (when (.existsSync fs p)
        (let [raw (.readFileSync fs p "utf8")
              data (js->clj (js/JSON.parse raw))
              k (get-in data [entry "key"])]
          (when (and (string? k) (pos? (count k))) k))))
    (catch :default _ nil)))

(defn api-key
  "The API key (string) of `gateway` (:zen default, or :go) or nil. Read fresh
   each call so a key added mid-session (e.g. after `opencode auth login`) is
   picked up without restart. Go's sign-in is not consulted here: see
   `credential`."
  ([] (api-key :zen))
  ([gateway]
   (let [zen-key (fn [] (or (env "OPENCODE_API_KEY")
                            (env "OPENCODE_ZEN_API_KEY")
                            (key-from-file "opencode")))]
     (case gateway
       :go (or (env "OPENCODE_GO_API_KEY")
               (key-from-file "opencode-go")
               (zen-key))
       (zen-key)))))

(defn credential
  "Promise of `gateway`'s credential {:token :org-id? :login? true?} — the
   bearer token plus the Console organization when the sign-in is scoped to
   one — or nil when none is configured. Rejects when the stored sign-in can
   no longer be refreshed."
  [gateway]
  (if (= :go gateway)
    (-> (login/access-token)
        (.then (fn [creds]
                 (if creds
                   (cond-> {:token (:access creds) :login? true}
                     (:org-id creds) (assoc :org-id (:org-id creds)))
                   (some->> (api-key :go) (hash-map :token))))))
    (js/Promise.resolve (some->> (api-key :zen) (hash-map :token)))))
