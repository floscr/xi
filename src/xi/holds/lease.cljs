(ns xi.holds.lease
  "Cross-process lease files — the storage behind xi.holds.

   A lease is a JSON file `{pid room label acquired-at touched-at}` created
   atomically (`open 'wx'`), so every Xi process on the machine (:7474, :7475,
   standalone TUIs) sees the same holder. The owner is {:pid :room} — a room
   key alone isn't unique across processes.

   A lease is stale (stealable) when its process is dead, or when the hold's
   own `stale?` says so. Everything here is synchronous (the clj worker thread
   settles leases itself) except `wait-acquire!` (main thread)."
  (:require ["node:fs" :as fs]
            ["node:path" :as node-path]))

(defn room-label
  "Human label for a room map — how a lease names its holder: the session
   name, else the cwd basename, else the room id."
  [room]
  (or (not-empty (get-in room [:session :name]))
      (some-> (:cwd room) node-path/basename)
      (some-> (:id room) name)))

(defn read-lease [path]
  (try
    (js->clj (js/JSON.parse (fs/readFileSync path "utf8")) :keywordize-keys true)
    (catch :default _ nil)))

(defn- write-lease! [path lease]
  (fs/writeFileSync path (js/JSON.stringify (clj->js lease))))

(defn- create-lease!
  "Atomically create the lease file; false when it already exists."
  [path lease]
  (try
    (let [fd (fs/openSync path "wx")]
      (fs/writeSync fd (js/JSON.stringify (clj->js lease)))
      (fs/closeSync fd)
      true)
    (catch :default _ false)))

(defn- rm-lease! [path]
  (try (fs/unlinkSync path) (catch :default _ nil)))

(defn pid-alive? [pid]
  (try (js/process.kill pid 0) true
       (catch :default e (= "EPERM" (.-code e)))))

(defn same-owner? [lease owner]
  (and (some? lease)
       (= (:pid lease) (:pid owner))
       (= (str (:room lease)) (str (:room owner)))))

(defn describe-holder
  "Human label for a lease holder."
  [lease]
  (str "\"" (or (not-empty (:label lease)) (:room lease) "?") "\""
       (when (not= (:pid lease) js/process.pid)
         (str " (another Xi process, pid " (:pid lease) ")"))))

(defn try-acquire!
  "One acquisition attempt for `owner` ({:pid :room :label}) on the lease at
   `path`. `stale?` (fn [lease] → bool) adds hold-specific staleness to the
   dead-pid check. → {:status :acquired|:busy, :holder lease, :fresh? bool};
   :fresh? marks a newly created lease (vs. re-entering one we hold)."
  [path owner stale?]
  (let [now   (js/Date.now)
        lease (read-lease path)]
    (cond
      (same-owner? lease owner)
      (do (write-lease! path (assoc lease :touched-at now))
          {:status :acquired :fresh? false})

      (and lease (pid-alive? (:pid lease)) (not (stale? lease)))
      {:status :busy :holder lease}

      :else
      (do (when (fs/existsSync path)
            ;; stale or corrupt — re-read so we only clear what we judged
            (when (= lease (read-lease path)) (rm-lease! path)))
          (if (create-lease! path (assoc owner :acquired-at now :touched-at now))
            {:status :acquired :fresh? true}
            {:status :busy :holder (read-lease path)})))))

(defn release!
  "Drop `owner`'s lease at `path`. True when released."
  [path owner]
  (when (same-owner? (read-lease path) owner)
    (rm-lease! path)
    true))

(defn force-release!
  "Drop whatever lease exists at `path`. Returns the dropped lease."
  [path]
  (let [lease (read-lease path)]
    (when (fs/existsSync path) (rm-lease! path))
    lease))

(def ^:private POLL_MS 2000)

(defn wait-acquire!
  "Poll try-acquire! until `owner` holds the lease at `path`.
   opts: {:path :owner :stale? :timeout-ms :cancelled? (fn [] → bool)
          :on-wait (fn [holder]) — called once, on the first busy poll}
   → Promise<{:status :acquired|:timeout|:cancelled :waited-ms :holder :fresh?}>"
  [{:keys [path owner stale? timeout-ms cancelled? on-wait]}]
  (let [start (js/Date.now)]
    (js/Promise.
     (fn [resolve _]
       (letfn [(attempt [notified?]
                 (let [{:keys [status holder] :as r} (try-acquire! path owner stale?)
                       waited (- (js/Date.now) start)
                       r      (assoc r :waited-ms waited)]
                   (cond
                     (not= :busy status)           (resolve r)
                     (and cancelled? (cancelled?)) (resolve (assoc r :status :cancelled))
                     (>= waited timeout-ms)        (resolve (assoc r :status :timeout))
                     :else
                     (do (when (and on-wait (not notified?))
                           (on-wait holder))
                         (js/setTimeout #(attempt true) POLL_MS)))))]
         (attempt false))))))
