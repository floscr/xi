(ns xi.web.user-state
  "The web client's side of per-user UI state (xi.user-state).

   Each key of the registry is bound to a state path and the effect that
   mirrors it into the browser (localStorage, the <html> theme attribute):

     server  ──:user-state/state──▶ on connect: the user's whole state
             ──:user-state/changed▶ another device (or an echo of our own
                                    write) changed one key
     client  ──:user-state/set────▶ a local change, sent by `set-effect`

   localStorage stays as the instant cache — it paints the right theme and
   layout before the socket connects, and covers offline use — but the server
   copy wins on connect. Two cases keep that safe:

   - Migration: keys the server has never stored are seeded from this
     browser's cached values, so existing settings follow the user to the
     server on first connect instead of resetting.
   - Shared browser: the cache remembers whose it is (`:web/cached-user`).
     When a different user connects, the cached values are dropped back to
     defaults rather than handed to — or seeded into — the new user.

   Pure: every function returns {:state :effects}; the :cache/* and :theme/*
   effects live in xi.web.core."
  (:require [xi.web.appearance :as appearance]
            [xi.web.theme :as theme]))

(def ^:private bindings
  "key → {:path   state path the value lives at
          :default the value of a user who never set it
          :coerce  server/wire value → state value (optional)
          :fx      state value → [effect …] mirroring it into the browser}"
  {:theme             {:path :web/theme-mode :default "auto"
                       :fx   (fn [v] [[:theme/apply v]])}
   :appearance        {:path :web/appearance :default {}
                       :coerce appearance/normalize
                       :fx   (fn [v] [[:cache/appearance {:settings v}]])}
   :themes            {:path :web/themes :default {}
                       :coerce theme/normalize
                       :fx   (fn [v] [[:cache/themes {:themes v}]
                                      [:theme/apply-vars {:vars (theme/active-vars v) :persist? true}]])}
   :sidebar-collapsed {:path :web/sidebar-collapsed :default #{}
                       :coerce set
                       :fx   (fn [v] [[:cache/sidebar-collapsed {:groups v}]])}
   :preferred-model   {:path :web/preferred-model :default nil
                       :fx   (fn [v] [[:cache/preferred-model {:model v}]])}
   :recent-commands   {:path :web/command-usage :default []
                       :coerce vec
                       :fx   (fn [v] [[:cache/recent-commands {:commands v}]])}
   :recent-skills     {:path :web/recent-skills :default []
                       :coerce vec
                       :fx   (fn [v] [[:cache/recent-skills {:skills v}]])}})

(defn- wire-value
  "State value → the vector/scalar the server's validators expect."
  [v]
  (if (set? v) (vec (sort-by str v)) v))

(defn set-effect
  "The effect that tells the server a local change to `k`. Handlers return it
   next to the existing cache effect."
  [k v]
  [:ws/send {:type :user-state/set :key k :value (wire-value v)}])

(defn- coerce [k v]
  ((or (get-in bindings [k :coerce]) identity) v))

(defn- apply-key
  "Install server value `v` for `k`: set the state path and mirror it into the
   browser. A value equal to the current one changes nothing — the echo of our
   own write must not re-run the theme transition."
  [st k v]
  (when-let [{:keys [path fx]} (get bindings k)]
    (let [v (coerce k v)]
      (when (not= v (get st path))
        {:state   (assoc st path v)
         :effects (fx v)}))))

(defn- thread
  "Run `(step state x)` — a {:state :effects} result, or nil for no change —
   over `xs`, threading the state through and concatenating the effects."
  [st step xs]
  (reduce (fn [acc x]
            (if-let [r (step (:state acc) x)]
              {:state (:state r) :effects (into (:effects acc) (:effects r))}
              acc))
          {:state st :effects []}
          xs))

(defn- locally-set?
  "Does this browser hold a non-default value for `k`? (candidates to seed)"
  [st k]
  (let [{:keys [path default]} (get bindings k)
        v (get st path)]
    (and (some? v)
         (not= v default)
         (not (and (coll? v) (empty? v))))))

(defn- reset-all
  "Back to defaults: used when the cache belonged to someone else."
  [st]
  (let [r (thread st
                  (fn [st [_ {:keys [path default fx]}]]
                    {:state (assoc st path default) :effects (fx default)})
                  bindings)]
    ;; the unread overlay is the other user's too; the server's :read for this
    ;; user comes with the lobby
    (-> r
        (update :state assoc :web/recent-commands [] :web/watched {})
        (update :effects conj [:cache/clear-watched {}]))))

(defn user-state
  "`:user-state/state {:user :state}` — the user's whole stored state, sent
   right after :auth/ok. Applies it over the cache (server wins), seeds keys
   the server lacks from this browser's cache, and records whose cache this
   is."
  [st {:keys [user state]}]
  (let [cached  (:web/cached-user st)
        mine?   (or (nil? cached) (= cached user))
        base    (if mine? {:state st :effects []} (reset-all st))
        applied (thread (:state base)
                        (fn [st [k v]] (apply-key st k v))
                        state)
        ;; the first connect of a browser that predates per-user state:
        ;; what it has cached is this user's, so keep it by writing it up
        seeds   (when mine?
                  (for [k (keys bindings)
                        :when (and (not (contains? state k))
                                   (locally-set? st k))]
                    (set-effect k (get st (get-in bindings [k :path])))))
        ;; the command bar order is frozen per page load (see record-command):
        ;; only a connect — not a later change — may reorder it
        st'     (cond-> (:state applied)
                  (contains? state :recent-commands)
                  (assoc :web/recent-commands (:web/command-usage (:state applied))))]
    {:state   st'
     :effects (-> []
                  (into (:effects base))
                  (into (:effects applied))
                  (into seeds)
                  (conj [:cache/user {:user user}]))}))

(defn user-state-changed
  "`:user-state/changed {:key :value}` — a device of ours changed `key` (or
   the echo of our own write). Same value → no-op."
  [st {:keys [key value]}]
  (apply-key st key value))

(def handlers
  {:user-state/state   user-state
   :user-state/changed user-state-changed})
