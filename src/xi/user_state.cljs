(ns xi.user-state
  "Per-user UI state — the state a person expects to follow them across
   devices: theme, appearance overrides, collapsed sidebar groups, the model
   new chats start with, recently used commands and skills.

   This is state, not configuration. Configuration (config.edn, rules, MCP
   servers, extensions) stays global to the server. User state used to live
   only in the browser's localStorage, so it belonged to a device; now the
   server keeps it per user id (xi.user-state.store) and every client of that
   user mirrors it (xi.web.user-state), with localStorage as the instant
   cache.

   Browser-safe and pure: this namespace is the registry of known keys and
   their validators. The server validates every client write against it —
   unknown keys and bad values are dropped — so a client can never grow the
   store with arbitrary data. To add a piece of per-user state, add a key
   here and bind it in xi.web.user-state.")

(defn- bounded-strings? [v max-count max-len]
  (and (sequential? v)
       (<= (count v) max-count)
       (every? #(and (string? %) (<= 1 (count %) max-len)) v)))

(def registry
  "Known keys → {:valid? (fn [value] → bool)}."
  {;; \"auto\" | \"light\" | \"dark\"
   :theme            {:valid? #{"auto" "light" "dark"}}
   ;; xi.web.appearance overrides: {:viewer-mode? bool :tool-blocks :open …}
   :appearance       {:valid? (fn [v]
                                (and (map? v)
                                     (<= (count v) 16)
                                     (every? (fn [[k x]]
                                               (and (keyword? k)
                                                    (or (boolean? x) (keyword? x))))
                                             v)))}
   ;; collapsed drawer groups, e.g. [:projects :recent]
   :sidebar-collapsed {:valid? (fn [v]
                                 (and (sequential? v)
                                      (<= (count v) 32)
                                      (every? keyword? v)))}
   ;; the model a new chat starts with
   :preferred-model  {:valid? (fn [v] (and (string? v) (<= 1 (count v) 200)))}
   ;; most-recent-first command / skill names for the quick bar and palette
   :recent-commands  {:valid? (fn [v] (bounded-strings? v 20 100))}
   :recent-skills    {:valid? (fn [v] (bounded-strings? v 20 100))}})

(defn valid?
  "Is `k` a known user-state key and `v` an allowed value for it?"
  [k v]
  (boolean (when-let [{:keys [valid?]} (get registry k)]
             (valid? v))))

(defn normalize
  "Keep only the known keys of `m` whose values are valid. Tolerates nil and
   non-map input (a missing, hand-edited or corrupt state file)."
  [m]
  (into {} (filter (fn [[k v]] (valid? k v))) (when (map? m) m)))
