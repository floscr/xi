(ns xi.api.user
  "Users for extensions: who is acting, what the operator declared about
   them, and a place for the extension to keep its own data per user.

     (current ctx)               → the user id this call acts for, e.g. alice
     (info ctx) (info ctx id)    → {:id :name :meta :ui}, read-only
     (users ctx)                 → [{:id :name} …] everyone the server knows
     (state ctx) (state ctx id)  → this extension's data for the user, or nil
     (set-state! ctx value)      → value, persisted for the current user
     (set-state! ctx id value)   → the same for another user (nil forgets it)

   Unlike the rest of xi.api these calls are synchronous: they read the
   server's own state and one small file. Not a rules request either: the
   state is the extension's own, like its data directory.

   What an extension can touch is fixed by who it is. The extension behind
   `ctx` is proven by its token (xi.api.core/caller), so it reads and writes
   only ITS entry of a user's state; other extensions' entries are not in what
   `info` returns, and `:meta` and `:ui` cannot be changed from here. A value
   must be plain data (nil, booleans, numbers, strings, keywords, and
   vectors / lists / sets / maps of those) under 64 KB printed; anything else
   throws and nothing is written. Writes persist to
   ~/.config/xi/state/users/<id>.edn and update the server's app state at
   once, so handlers see them as `(xi.core.state/user-ext st id :my-ext)`.

   `current` is the user whose prompt started the turn (a tool call carries
   it as :user) and otherwise the user the server process itself acts as.
   `users`, `info` and any `id` argument also work for a user nobody declared:
   a user is whoever a connection says it is."
  (:require [xi.api.core :as core]
            [xi.core.state :as core-state]
            [xi.users :as xi-users]
            [xi.util :as util]))

(defn- host []
  (or (core/get-app-host)
      (throw (ex-info "xi.api.user: user state isn't available in this process" {}))))

(defn- resolve-user
  "The id a call is about: `id` when given (it must already be a valid id),
   else the ctx's :user, else the process' own user."
  [ctx id]
  (cond
    (some? id)
    (if (and (string? id) (= id (util/user-id id)))
      id
      (throw (ex-info (str "xi.api.user: not a user id: " (pr-str id)
                           " (lowercase letters, digits, '.', '_' or '-')")
                      {})))

    (:user ctx) (:user ctx)

    :else
    (core-state/own-user ((:get-state (host))))))

(defn current
  "The id of the user this call acts for."
  [ctx]
  (core/caller ctx)
  (resolve-user ctx nil))

(defn info
  "→ {:id :name :meta :ui} of a user, read-only: the operator's :name and
   :meta from config.edn and the user's UI state. Nothing of any extension's
   own state is included."
  ([ctx] (info ctx nil))
  ([ctx id]
   (core/caller ctx)
   (-> (xi-users/user (host) (resolve-user ctx id))
       (select-keys [:id :name :meta :ui]))))

(defn users
  "→ [{:id :name} …], sorted by id: every user the server knows."
  [ctx]
  (core/caller ctx)
  (xi-users/user-list (host)))

(defn state
  "→ what THIS extension keeps for the user (the current one, or `id`), nil
   when nothing."
  ([ctx] (state ctx nil))
  ([ctx id]
   (let [ext (core/caller ctx)]
     (xi-users/ext-state (host) (resolve-user ctx id) ext))))

(defn set-state!
  "Keep `value` as this extension's data for the user (the current one, or
   `id`), persisted. nil forgets it. → value. Throws, writing nothing, when
   `value` is not plain data or is too large."
  ([ctx value] (set-state! ctx nil value))
  ([ctx id value]
   (let [ext (core/caller ctx)]
     (xi-users/set-ext-state! (host) (resolve-user ctx id) ext value))))
