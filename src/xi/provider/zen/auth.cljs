(ns xi.provider.zen.auth
  "Resolve the OpenCode Zen API key.

   Resolution order:
     1. OPENCODE_API_KEY env var
     2. OPENCODE_ZEN_API_KEY env var
     3. OpenCode's own credential store at
        ~/.local/share/opencode/auth.json → {\"opencode\":{\"type\":\"api\",\"key\":\"sk-…\"}}

   Returns the key string, or nil when none is configured. A nil key is fine
   for the free chat-completions models (they don't require auth); paid models
   and the Anthropic/Responses surfaces need a real key."
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]))

(defn- env [k]
  (let [v (aget js/process.env k)]
    (when (and (string? v) (pos? (count v))) v)))

(defn- opencode-auth-path []
  (path/join (os/homedir) ".local" "share" "opencode" "auth.json"))

(defn- key-from-file []
  (try
    (let [p (opencode-auth-path)]
      (when (.existsSync fs p)
        (let [raw (.readFileSync fs p "utf8")
              data (js->clj (js/JSON.parse raw) :keywordize-keys true)
              k (get-in data [:opencode :key])]
          (when (and (string? k) (pos? (count k))) k))))
    (catch :default _ nil)))

(defn api-key
  "Resolve the Zen API key (string) or nil. Read fresh each call so a key added
   mid-session (e.g. after `opencode auth login`) is picked up without restart."
  []
  (or (env "OPENCODE_API_KEY")
      (env "OPENCODE_ZEN_API_KEY")
      (key-from-file)))
