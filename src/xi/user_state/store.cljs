(ns xi.user-state.store
  "Per-user UI state on disk (node only): one EDN file per user id,
   ~/.config/xi/state/users/<user>.edn, holding the keys of xi.user-state's
   registry. The root user is just `root.edn`. Written atomically (temp file,
   rename), mode 0600, and read back through `xi.user-state/normalize`, so a
   corrupt or hand-edited file degrades to defaults instead of breaking a
   connection. User ids are slugs (xi.util/user-id), so a client-claimed id
   can never escape the directory."
  (:require [cljs.reader :as reader]
            [xi.session :as session]
            [xi.user-state :as user-state]
            [xi.util :as util]))

(def ^:private fs (js/require "node:fs"))
(def ^:private path (js/require "node:path"))
(def ^:private os (js/require "node:os"))

(def ^:private MODE-0600 384)

(defonce ^:private dir-override (atom nil))

(defn set-dir!
  "Override the state directory (nil restores the default). Test seam, like
   xi.user-config/set-config-file!."
  [dir]
  (reset! dir-override dir))

(defn dir []
  (or @dir-override
      (.join path (.homedir os) ".config" "xi" "state" "users")))

(defn file [user]
  (.join path (dir) (str (util/user-id user) ".edn")))

(defn load-state
  "The user's stored state, {} when the file is missing or unreadable."
  [user]
  (try
    (let [f (file user)]
      (if (.existsSync fs f)
        (user-state/normalize (reader/read-string (.readFileSync fs f "utf8")))
        {}))
    (catch :default _ {})))

(defn- write! [user m]
  (let [f   (file user)
        tmp (str f ".tmp")]
    (.mkdirSync fs (dir) #js {:recursive true})
    (.writeFileSync fs tmp (str (pr-str m) "\n") #js {:mode MODE-0600})
    (.renameSync fs tmp f)
    true))

(defn set-key!
  "Store `v` under `k` for `user`. True when written; false (nothing
   touched) when the key is unknown or the value invalid."
  [user k v]
  (when (user-state/valid? k v)
    (write! user (assoc (load-state user) k v))))

(defn ext-state
  "What extension `ext-id` keeps about `user`, nil when nothing."
  [user ext-id]
  (get-in (load-state user) [:ext ext-id]))

(defn set-ext!
  "Keep `value` as extension `ext-id`'s state for `user` (nil forgets it).
   True when written; false (nothing touched) when it is not plain data, is
   too large, or the user already holds the maximum number of extensions."
  [user ext-id value]
  (when (keyword? ext-id)
    (let [state (load-state user)
          ext   (if (nil? value)
                  (dissoc (:ext state) ext-id)
                  (assoc (:ext state) ext-id value))]
      (when (user-state/valid? :ext ext)
        (write! user (assoc state :ext ext))))))

;; ── Read markers and hidden chats ───────────────────────────────────────────
;; What the lobby shows each user differently: which chats they have caught up
;; with, and which they hid from Recent. Server-kept (never on the client wire).

(defn mark-read!
  "Record `session-id` as seen up to `n` responses by `user`, on top of
   `base` (the {session-id count} map the user's markers currently read as; it
   is not always what is stored, see xi.server.ws/read-state-of). Past the cap
   the map keeps an arbitrary `max-read-state` of its entries."
  [user base session-id n]
  (let [m (assoc base session-id n)]
    (set-key! user :read-state
              (if (> (count m) user-state/max-read-state)
                (into {session-id n} (take (dec user-state/max-read-state)) (dissoc m session-id))
                m))))

(defn dismissed
  "The set of session ids `user` hid from Recent."
  [user]
  (set (:dismissed (load-state user))))

(defn toggle-dismissed!
  "Hide `session-id` from `user`'s Recent, or show it again. Returns the new
   dismissed? state, nil when nothing could be written."
  [user session-id]
  (let [v       (vec (:dismissed (load-state user)))
        hidden? (boolean (some #{session-id} v))
        v'      (if hidden?
                  (filterv #(not= session-id %) v)
                  (vec (take-last user-state/max-dismissed (conj v session-id))))]
    (when (set-key! user :dismissed v')
      (not hidden?))))

(defn undismiss!
  "Show `session-id` in `user`'s Recent again; a no-op (nothing written) when
   it was not hidden."
  [user session-id]
  (let [v (vec (:dismissed (load-state user)))]
    (when (some #{session-id} v)
      (set-key! user :dismissed (filterv #(not= session-id %) v)))))

(defn known-users
  "The ids that have a state file, sorted."
  []
  (try
    (->> (.readdirSync fs (dir))
         (keep #(second (re-matches #"(.+)\.edn" %)))
         sort
         vec)
    (catch :default _ [])))

;; ── Favorites ──────────────────────────────────────────────────────────────────────────────────
;; The chats a user starred. A user who never starred one starts from the old
;; global ~/.config/xi/favorites.json (read-only now), so the upgrade doesn't
;; empty everyone's favorites.

(defn favorites
  "The session ids `user` starred, oldest star first."
  [user]
  (or (:favorites (load-state user))
      (session/load-legacy-favorites)))

(defn favorite-ids
  "The set of session ids `user` starred."
  [user]
  (set (favorites user)))

(defn toggle-favorite!
  "Star `session-id` for `user`, or unstar it. Returns the new favorite?
   state, nil when nothing could be written."
  [user session-id]
  (let [v    (favorites user)
        fav? (boolean (some #{session-id} v))
        v'   (if fav?
               (filterv #(not= session-id %) v)
               (vec (take-last user-state/max-favorites (conj v session-id))))]
    (when (set-key! user :favorites v')
      (not fav?))))

(defn all-favorite-ids
  "Every session id any user starred (plus the legacy global ones): what the
   shared lobby list must keep however old they are, since each user's own
   stars are only applied when it is sent to them."
  []
  (into (set (session/load-legacy-favorites))
        (mapcat #(:favorites (load-state %)))
        (known-users)))

