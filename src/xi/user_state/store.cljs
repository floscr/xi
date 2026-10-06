(ns xi.user-state.store
  "Per-user UI state on disk (node only): one EDN file per user id,
   ~/.config/xi/state/users/<user>.edn, holding the keys of xi.user-state's
   registry. The root user is just `root.edn`. Written atomically (temp file,
   rename), mode 0600, and read back through `xi.user-state/normalize`, so a
   corrupt or hand-edited file degrades to defaults instead of breaking a
   connection. User ids are slugs (xi.util/user-id), so a client-claimed id
   can never escape the directory."
  (:require [cljs.reader :as reader]
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

(defn known-users
  "The ids that have a state file, sorted."
  []
  (try
    (->> (.readdirSync fs (dir))
         (keep #(second (re-matches #"(.+)\.edn" %)))
         sort
         vec)
    (catch :default _ [])))
