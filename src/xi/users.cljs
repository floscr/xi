(ns xi.users
  "Users as data the server holds and extensions can reach (node only).

   A user is an id (xi.util/user-id). Three things hang off it:

     profile  :name and :meta, declared in config.edn `:users`
              (xi.user-config) — read-only, the operator's say.
     ui       the user's UI state: theme, collapsed groups, … (xi.user-state).
     ext      what each extension keeps about the user, {ext-id data}.

   `ui` and `ext` persist to ~/.config/xi/state/users/<id>.edn
   (xi.user-state.store). The three are loaded into app state as
   `[:users id]` = {:id :name :meta :ui :ext} when a user connects (and for
   the process' own user at startup), so handlers and extensions read them
   with plain state access (xi.core.state/user-record, `user-ext`).

   Reading is state access; *writing* an extension's state goes through
   `set-ext-state!`, which validates, persists and updates `[:users …]` — the
   one place that does. Extensions reach it through xi.api.user, which proves
   which extension is calling; handlers cannot write `:users` at all
   (xi.ext.user.guard), and clients cannot forge the events that do
   (xi.server.ws drops them)."
  (:require [clojure.string :as str]
            [xi.avatar :as avatar]
            [xi.user-config :as user-config]
            [xi.user-state :as user-state]
            [xi.user-state.store :as store]
            [xi.util :as util]))

(defn- profile [user]
  (select-keys (get (user-config/users) user) [:name :meta]))

(defn record
  "User `id`'s record read from config + disk: {:id :name :meta :ui :ext}.
   Always works, also for a user nobody declared or who never connected."
  [id]
  (let [id    (util/user-id id)
        prof  (profile id)
        state (store/load-state id)]
    {:id   id
     :name (:name prof)
     :meta (or (:meta prof) {})
     :ui   (user-state/client-view state)
     :ext  (or (:ext state) {})}))

(defn loaded-event
  "The `:user/loaded` event that installs `id`'s record into app state."
  [id]
  (let [{:keys [name meta ui ext]} (record id)]
    {:type :user/loaded :user (util/user-id id)
     :profile {:name name :meta meta} :ui ui :ext ext}))

(defn user
  "The record of `id` for `host` ({:get-state …}): the loaded one from app
   state, else read from disk (a user who has not connected yet)."
  [{:keys [get-state]} id]
  (let [id (util/user-id id)
        st (when get-state (get-state))]
    (or (get-in st [:users id])
        (record id))))

(defn ext-state
  "What extension `ext-id` keeps about user `id`, nil when nothing."
  [host id ext-id]
  (get-in (user host id) [:ext ext-id]))

(defn declared-ids
  "The users the config declares plus root, sorted: who a client may switch to.
   Rides on the lobby payload as :user-ids; only a list with more than one
   entry gives the web client a user switcher."
  []
  (vec (sort (conj (set (keys (user-config/users))) util/root-user))))

(defn public-profiles
  "The public profile ({:name :avatar}, see xi.avatar) of every declared user
   and of everyone attached to one of `rooms` (room summaries with :users),
   {id profile}. Rides on the lobby payload so any client can draw a user's
   avatar; :meta never leaves the server."
  [rooms]
  (let [declared (user-config/users)]
    (avatar/profiles declared (into (set (keys declared)) (mapcat :users) rooms))))

(defn user-list
  "Every user the process knows, [{:id :name}] sorted by id: the declared
   ones, the loaded ones, anyone with a state file, and root."
  [{:keys [get-state]}]
  (let [declared (user-config/users)
        loaded   (when get-state (:users (get-state)))
        ids      (into #{util/root-user}
                       (concat (keys declared) (keys loaded) (store/known-users)))]
    (->> ids
         sort
         (mapv (fn [id] {:id id :name (:name (get declared id))})))))

(defn set-ext-state!
  "Keep `value` as extension `ext-id`'s state for user `id` (nil forgets it):
   validated (plain data, bounded size — xi.user-state/ext-value?), written to
   the user's state file, then `[:users id :ext ext-id]` is updated through
   the `:user/ext-set` event. → `value`. Throws, writing nothing, when the
   value is refused. `host` is {:dispatch! …}."
  [{:keys [dispatch!]} id ext-id value]
  (let [id (util/user-id id)]
    (when-not (keyword? ext-id)
      (throw (ex-info "xi.users: the extension id must be a keyword" {})))
    (when-not (or (nil? value) (user-state/plain-data? value))
      (throw (ex-info (str "xi.users: user state must be plain data (nil, booleans, numbers, "
                           "strings, keywords, vectors, lists, sets, maps), not "
                           (pr-str (type value)))
                      {})))
    (when-not (or (nil? value) (user-state/ext-value? value))
      (throw (ex-info (str "xi.users: user state is too large (more than "
                           user-state/max-ext-bytes " bytes printed)")
                      {})))
    (when-not (store/set-ext! id ext-id value)
      (throw (ex-info "xi.users: the user's state was refused (too many extensions keep state for them)"
                      {})))
    (when dispatch!
      (dispatch! {:type :user/ext-set :user id :ext ext-id :value value}))
    value))
