(ns xi.providers.locks
  "Named mutexes the provider processes share (the protocol's `lock` /
   `unlock` frames): one holder per name, waiters served in order. A
   process's locks go when it exits. In memory, so they span this Xi
   process's requests, as the in-process guards they replace did.")

(defonce ^:private !locks
  ;; name → {:holder owner :queue [[owner resolve] …]}
  (atom {}))

(defn acquire!
  "Promise resolving once `owner` (any identity, one per request) holds
   `name`."
  [name owner]
  (js/Promise.
   (fn [resolve _]
     (let [granted? (atom false)]
       (swap! !locks update name
              (fn [{:keys [holder] :as l}]
                (if (nil? holder)
                  (do (reset! granted? true) {:holder owner :queue (:queue l [])})
                  (update l :queue (fnil conj []) [owner resolve]))))
       (when @granted? (resolve nil))))))

(defn- hand-over [{:keys [queue]}]
  (if-let [[owner resolve] (first queue)]
    [{:holder owner :queue (vec (rest queue))} resolve]
    [nil nil]))

(defn release!
  "`owner` lets go of `name`; the next waiter gets it."
  [name owner]
  (let [next-resolve (atom nil)]
    (swap! !locks
           (fn [locks]
             (let [l (get locks name)]
               (if (identical? owner (:holder l))
                 (let [[l' resolve] (hand-over l)]
                   (reset! next-resolve resolve)
                   (if l' (assoc locks name l') (dissoc locks name)))
                 locks))))
    (when-let [r @next-resolve] (r nil))))

(defn release-all!
  "Drop everything `owner` holds or waits for (its process exited)."
  [owner]
  (doseq [[name l] @!locks]
    (when (some #(identical? owner (first %)) (:queue l))
      (swap! !locks update name update :queue
             (fn [q] (vec (remove #(identical? owner (first %)) q)))))
    (when (identical? owner (:holder l))
      (release! name owner))))

(defn held
  "name → holder, for tests and diagnostics."
  []
  (into {} (map (fn [[k v]] [k (:holder v)])) @!locks))
