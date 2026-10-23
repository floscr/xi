(ns xi.api.store
  "A key-value store for user extensions: plain data under keys the
   extension picks, kept on disk under its own id
   (`<data-dir>/store/<key>.edn`, see xi.paths/extension-data-dir), so it
   survives restarts, reloads and closed rooms. The caller is resolved from
   the ctx's token (xi.api.core/caller): an extension only ever reaches its
   own entries. No rules request, the data dir is the extension's.

   Keys are strings or simple keywords made of letters, digits, `.`, `_` and
   `-` (no leading dot); `:plan` and \"plan\" name the same entry. Values are
   plain data (maps, vectors, sets, lists, strings, numbers, keywords,
   symbols, booleans, nil, uuids, dates). Putting nil removes the entry.

   Calls on one key run in the order they were made, so a read sees every
   write made before it, and a file is replaced whole (written aside, then
   renamed): a crash never leaves half a value."
  (:refer-clojure :exclude [get keys])
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            [xi.api.core :as core]
            [xi.paths :as paths]
            ["node:fs" :as fs]
            ["node:path" :as node-path]))

(def ^:private key-re #"[A-Za-z0-9_-][A-Za-z0-9._-]{0,199}")

(defn- key-name [k]
  (let [s (cond (string? k)                           k
                (and (keyword? k) (nil? (namespace k))) (name k))]
    (if (and s (re-matches key-re s))
      s
      (throw (ex-info (str "xi.api.store: not a key: " (pr-str k)
                           " (a string or simple keyword of letters, digits, . _ -)")
                      {:key k})))))

(defn- plain? [x]
  (cond
    (or (nil? x) (boolean? x) (number? x) (string? x) (keyword? x) (symbol? x)
        (uuid? x) (inst? x))
    true
    (map? x)  (every? (fn [[k v]] (and (plain? k) (plain? v))) x)
    (coll? x) (every? plain? x)
    :else     false))

(defn- store-dir [id] (node-path/join (paths/extension-data-dir id) "store"))

(defn- file [id k] (node-path/join (store-dir id) (str (key-name k) ".edn")))

;; [id key-name] → the promise of the last call on that key
(defonce ^:private queues (atom {}))

(defn- queued
  "Run `f` (→ value or promise) after every earlier call on the same key."
  [id k f]
  (let [q    [id (key-name k)]
        prev (or (clojure.core/get @queues q) (js/Promise.resolve))
        run  (.then prev (fn [_] (f)))
        tail (.catch run (fn [_] nil))]
    (swap! queues assoc q tail)
    (.then tail (fn [_] (when (identical? tail (clojure.core/get @queues q))
                          (swap! queues dissoc q))))
    run))

(defn- caller [ctx]
  (js/Promise. (fn [resolve] (resolve (core/caller ctx)))))

(defn get
  "→ Promise of the value stored under `k`, or nil."
  [ctx k]
  (.then (caller ctx)
         (fn [id]
           (queued id k
                   (fn []
                     (let [f (file id k)]
                       (when (fs/existsSync f)
                         (.then (.readFile (.-promises fs) f "utf8")
                                reader/read-string))))))))

(defn put!
  "Store `value` under `k` (nil removes it) → Promise of nil."
  [ctx k value]
  (.then (caller ctx)
         (fn [id]
           (when-not (plain? value)
             (throw (ex-info (str "xi.api.store: not plain data under " (pr-str k)) {:key k})))
           (queued id k
                   (fn []
                     (let [f   (file id k)
                           tmp (str f ".tmp")
                           p   (.-promises fs)]
                       (if (nil? value)
                         (.then (.rm p f #js {:force true}) (constantly nil))
                         (-> (.mkdir p (store-dir id) #js {:recursive true})
                             (.then #(.writeFile p tmp (pr-str value) "utf8"))
                             (.then #(.rename p tmp f))
                             (.then (constantly nil))))))))))

(defn delete!
  "Remove the entry under `k` → Promise of nil."
  [ctx k]
  (put! ctx k nil))

(defn keys
  "→ Promise of the stored keys, as sorted strings."
  [ctx]
  (.then (caller ctx)
         (fn [id]
           (let [dir (store-dir id)]
             (if (fs/existsSync dir)
               (.then (.readdir (.-promises fs) dir)
                      (fn [names]
                        (->> names
                             (keep #(when (str/ends-with? % ".edn") (subs % 0 (- (count %) 4))))
                             sort
                             vec)))
               [])))))
