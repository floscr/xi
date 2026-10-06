(ns xi.avatar
  "How a user shows up: an avatar. Pure data helpers shared by the server
   (which publishes each user's public profile on the lobby payload) and the
   web client (which draws it).

   A profile is {:name :avatar}, both optional, declared in config.edn
   `:users` (xi.user-config). `:avatar` is an http(s) image URL; a user
   without one gets a circle with initials on a colour derived from their id,
   so every user is distinguishable with no setup."
  (:require [clojure.string :as str]))

(def max-url-length 2048)

(defn url?
  "True when `s` is usable as an avatar: an http(s) URL with no whitespace.
   Nothing else (javascript:, data:, relative paths) reaches an <img src>."
  [s]
  (and (string? s)
       (<= (count s) max-url-length)
       (boolean (re-matches #"https?://[^\s]+" s))))

(defn public-profile
  "What other users may see of `profile` (a declared {:name :meta :avatar}):
   :name and :avatar only — :meta stays server-side."
  [profile]
  (cond-> {}
    (:name profile)           (assoc :name (:name profile))
    (url? (:avatar profile))  (assoc :avatar (:avatar profile))))

(defn profiles
  "Public profiles for user `ids`: {id {:name :avatar}}, an id nobody declared
   getting {}. `declared` is the config's :users map."
  [declared ids]
  (into {} (map (fn [id] [id (public-profile (get declared id))])) ids))

(defn initials
  "Up to two capital letters for a user: the first letters of the words in
   their name, else of their id (split on space . _ -)."
  [id name]
  (let [src (if (str/blank? name) (str id) name)]
    (->> (str/split (str/trim src) #"[\s._-]+")
         (remove str/blank?)
         (take 2)
         (map #(str/upper-case (subs % 0 1)))
         str/join)))

(defn hue
  "A stable hue (0-359) for user `id`, the avatar's background colour."
  [id]
  (mod (reduce (fn [h c]
                 (mod (+ (* h 31) #?(:clj (int c) :cljs (.charCodeAt c 0))) 360000))
               7
               (str id))
       360))
