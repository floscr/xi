(ns xi.user-state
  "Per-user UI state — the state a person expects to follow them across
   devices: theme, appearance overrides, collapsed sidebar groups, the model
   new chats start with, recently used commands and skills.

   This is state, not configuration. Configuration (config.edn, rules, MCP
   servers, extensions) stays global to the server; the one exception is
   `:users` in config.edn, which declares who exists (xi.user-config). User
   state used to live
   only in the browser's localStorage, so it belonged to a device; now the
   server keeps it per user id (xi.user-state.store) and every client of that
   user mirrors it (xi.web.user-state), with localStorage as the instant
   cache.

   Browser-safe and pure: this namespace is the registry of known keys and
   their validators. The server validates every client write against it —
   unknown keys and bad values are dropped — so a client can never grow the
   store with arbitrary data. To add a piece of per-user state, add a key
   here and bind it in xi.web.user-state.

   Three keys are not UI state and are kept by the server alone — clients can
   neither read nor write them. `:ext` holds what each extension keeps about
   the user, {ext-id data}; extensions reach it through xi.api.user, which
   proves who is calling, so an extension only ever touches its own entry.
   `:read-state` and `:dismissed` are the user's unread markers and the chats
   they hid from Recent: the lobby is built per user from them (xi.server.ws),
   so they reach a client as the lobby's `:read` and `:dismissed?` flags. An
   extension's entry may also hold `:session-flags`, which the lobby turns into
   more flags the same way (see `session-flags`)."
  (:require [clojure.string :as str]))

(def reserved-flags
  "Session flags an extension may not set: the lobby and the sidebar compute
   these themselves."
  #{:dismissed? :pinned? :active? :busy? :unread? :current? :has-dialog? :error?})

(defn session-flags
  "The flags the extensions keep for a user, {flag-key #{session-id}}, from the
   `:session-flags` ({flag-key [session-id …]}) in each entry of `stored`'s
   `:ext`. A flag is a keyword ending in `?` that is not one of
   `reserved-flags`; anything else in the entry is ignored. Two extensions
   keeping the same flag add up."
  [stored]
  (reduce (fn [acc [flag ids]]
            (if (and (keyword? flag)
                     (str/ends-with? (name flag) "?")
                     (not (reserved-flags flag))
                     (sequential? ids))
              (update acc flag (fnil into #{}) (filter string? ids))
              acc))
          {}
          (mapcat (fn [[_ entry]]
                    (let [m (when (map? entry) (:session-flags entry))]
                      (when (map? m) m)))
                  (:ext stored))))

(defn extension-flags
  "The extension flags tagged on session summary `s` (see `session-flags`), as
   booleans: every keyword ending in `?` that is not one of `reserved-flags`."
  [s]
  (into {}
        (keep (fn [[k v]]
                (when (and (keyword? k)
                           (str/ends-with? (name k) "?")
                           (not (reserved-flags k)))
                  [k (boolean v)])))
        s))

(defn annotate-session-flags
  "Tag each summary with every flag of `flags` ({flag-key #{session-id}}):
   true when its session id is in the set, false otherwise."
  [summaries flags]
  (mapv (fn [s]
          (reduce-kv (fn [s flag ids] (assoc s flag (contains? ids (:session-id s))))
                     s flags))
        summaries))

(defn plain-data?
  "Is `v` plain EDN data — nil, booleans, numbers, strings, keywords, and
   vectors / lists / sets / maps of those — of bounded depth and size? What an
   extension may keep as user state: it must survive a round trip through the
   state file, so no functions, atoms, JS objects or symbols."
  [v]
  (let [n (volatile! 0)]
    (letfn [(ok? [x depth]
              (vswap! n inc)
              (and (<= depth 12)
                   (<= @n 5000)
                   (cond
                     (or (nil? x) (boolean? x) (number? x) (string? x) (keyword? x)) true
                     (map? x)  (every? (fn [[k val]] (and (ok? k (inc depth)) (ok? val (inc depth)))) x)
                     (or (sequential? x) (set? x)) (every? #(ok? % (inc depth)) x)
                     :else false)))]
      (ok? v 0))))

(def max-ext-bytes
  "Most state one extension may keep for one user, as printed EDN."
  65536)

(defn ext-value?
  "May an extension keep `v` as a user's state? Plain data, bounded in size."
  [v]
  (and (plain-data? v)
       (<= (count (pr-str v)) max-ext-bytes)))

(defn- bounded-strings? [v max-count max-len]
  (and (sequential? v)
       (<= (count v) max-count)
       (every? #(and (string? %) (<= 1 (count %) max-len)) v)))

(def max-read-state
  "Most chats one user's read markers cover."
  20000)

(def max-dismissed
  "Most chats one user keeps hidden from Recent."
  1000)

(def max-pinned
  "Most chats one user keeps pinned to Recent."
  200)

(def registry
  "Known keys → {:valid? (fn [value] → bool)}."
  {;; \"auto\" | \"light\" | \"dark\"
   :theme            {:valid? #{"auto" "light" "dark"}}
   ;; xi.web.appearance overrides: {:super-collapsed? bool :tool-blocks :open …}
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
   :recent-skills    {:valid? (fn [v] (bounded-strings? v 20 100))}
   ;; Server-kept like :ext: the lobby carries each user's own copy
   ;; (xi.server.ws), so a client neither reads nor writes these.
   ;; {session-id seen-response-count}: a chat is unread past its count
   :read-state       {:client-writable? false
                      :valid? (fn [m]
                                (and (map? m)
                                     (<= (count m) max-read-state)
                                     (every? (fn [[k n]]
                                               (and (string? k) (<= 1 (count k) 200)
                                                    (integer? n) (not (neg? n))))
                                             m)))}
   ;; session ids hidden from the user's Recent group, oldest first. Disjoint
   ;; from :pinned: hiding unpins, pinning un-hides (xi.user-state.store)
   :dismissed        {:client-writable? false
                      :valid? (fn [v] (bounded-strings? v max-dismissed 200))}
   ;; session ids pinned to the user's Recent group: never aged out of it,
   ;; skipped by "Hide all from Recent", oldest first
   :pinned           {:client-writable? false
                      :valid? (fn [v] (bounded-strings? v max-pinned 200))}
   ;; {ext-id data}: what each extension keeps about the user. Written only
   ;; by the server (xi.users), never by a client.
   :ext              {:client-writable? false
                      :valid? (fn [m]
                                (and (map? m)
                                     (<= (count m) 32)
                                     (every? (fn [[k v]] (and (keyword? k) (ext-value? v))) m)))}})

(defn valid?
  "Is `k` a known user-state key and `v` an allowed value for it?"
  [k v]
  (boolean (when-let [{:keys [valid?]} (get registry k)]
             (valid? v))))

(defn client-valid?
  "`valid?`, and the key is one a client may write (not `:ext`)."
  [k v]
  (boolean (and (valid? k v)
                (not (false? (get-in registry [k :client-writable?]))))))

(defn normalize
  "Keep only the known keys of `m` whose values are valid. Tolerates nil and
   non-map input (a missing, hand-edited or corrupt state file)."
  [m]
  (into {} (filter (fn [[k v]] (valid? k v))) (when (map? m) m)))

(defn client-view
  "The part of a user's state a client may see: everything but `:ext`."
  [m]
  (into {}
        (remove (fn [[k _]] (false? (get-in registry [k :client-writable?]))))
        m))
