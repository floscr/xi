(ns xi.ext.process-manager
  "Track and control agent-started background processes.

   The processes themselves are spawned by the clj sandbox's `process`
   namespace (xi.ext.clj-process — (process/start \"cmd\") on the clj worker
   thread, permission-gated like sh). The worker mirrors every register/
   deregister here via :ext.process-manager/register / :deregister events, so
   this extension only keeps the room-scoped registry: it drives /ps, /kill,
   the /processes picker, room keep-alive (see room-manager), and kills
   whatever is still running when the room closes. The web client's process
   chip opens a process's log as a `:process-log` buffer that follows the
   file while the process runs (xi.ext.process-manager.web).

   State is room-scoped (xi.ext.process-manager.registry):
     {:processes [{:pid N :command str :started ms-timestamp :logfile str}]}"
  (:require [clojure.string :as str]
            [xi.ext.clj-process :as proc]
            [xi.ext.process-manager.registry :as reg]))

(def ^:private ext-id reg/ext-id)

(def ^:private get-processes reg/get-processes)

;; ── Helpers ──────────────────────────────────────────────────────────────────

(defn- kill-pid!
  "SIGTERM a process group by PID (the spawn is detached, so the pid is its
   group leader), falling back to the single pid. True when a signal was
   delivered."
  [pid]
  (try
    (.kill js/process (- pid) "SIGTERM")
    true
    (catch :default _e
      (try
        (.kill js/process pid "SIGTERM")
        true
        (catch :default _e false)))))

(defn- format-duration
  "Human-readable duration from ms elapsed."
  [ms]
  (let [secs  (quot ms 1000)
        mins  (quot secs 60)
        hours (quot mins 60)]
    (cond
      (>= hours 1) (str hours "h " (mod mins 60) "m")
      (>= mins 1)  (str mins "m " (mod secs 60) "s")
      :else         (str secs "s"))))

(defn- format-process-list
  "Render the tracked processes as a text block (for /ps)."
  [procs]
  (if (empty? procs)
    "No tracked processes."
    (let [now (.now js/Date)]
      (str "Tracked processes:\n"
           (str/join
            "\n"
            (map-indexed
             (fn [i {:keys [pid command started logfile]}]
               (let [tail (when logfile (proc/tail-file logfile 3))]
                 (str "  " (inc i) ". ["
                      (if (proc/pid-alive? pid) "alive" "dead") "] "
                      "PID " pid
                      " (" (format-duration (- now started)) ") — " command
                      (when logfile (str "\n       logs: " logfile))
                      (when (seq tail)
                        (str "\n       recent output:\n"
                             (str/join "\n" (map #(str "       | " %)
                                                 (str/split-lines tail))))))))
             procs))))))

(defn- parse-kill-target
  "Parse a kill/stop target — a 1-based index or a PID."
  [arg procs]
  (when-let [n (parse-long (str/trim (str (or arg ""))))]
    (if (<= 1 n (count procs))
      ;; Looks like an index
      (nth procs (dec n))
      ;; Try as PID
      (some #(when (= n (:pid %)) %) procs))))

;; ── Handlers ─────────────────────────────────────────────────────────────────

(defn- on-room-close
  "Kill all tracked processes when a room is destroyed."
  [st {:keys [room-id]}]
  (let [procs (get-processes st room-id)]
    (doseq [{:keys [pid]} procs]
      (kill-pid! pid))
    nil))

;; ── Commands ─────────────────────────────────────────────────────────────────

(defn- cmd-ps
  "List tracked background processes with status."
  [st {:keys [room-id]}]
  {:state (update-in st [:rooms room-id :history] conj
                     {:kind :status
                      :text (format-process-list (get-processes st room-id))})})

(defn- cmd-kill
  "Kill a tracked process by index or PID."
  [st {:keys [room-id args]}]
  (let [procs (get-processes st room-id)
        arg   (str/trim (or args ""))]
    (cond
      (empty? procs)
      {:state (update-in st [:rooms room-id :history] conj
                         {:kind :status :text "No tracked processes."})}

      (str/blank? arg)
      {:state (update-in st [:rooms room-id :history] conj
                         {:kind :status :text "Usage: /kill <index|pid>"})}

      :else
      (if-let [{:keys [pid command]} (parse-kill-target arg procs)]
        (let [killed (kill-pid! pid)
              procs' (vec (remove #(= pid (:pid %)) procs))]
          {:state (-> st
                      (assoc-in [:rooms room-id :ext ext-id :processes] procs')
                      (update-in [:rooms room-id :history] conj
                                 {:kind :status
                                  :text (if killed
                                          (str "Killed PID " pid " — " command)
                                          (str "PID " pid " already dead — removed from list"))}))})
        {:state (update-in st [:rooms room-id :history] conj
                           {:kind :status
                            :text (str "No process matching: " arg)})}))))

(defn- process-menu-item
  "Build a picker item for one tracked process. Enter dispatches a kill."
  [now room-id {:keys [pid command started logfile]}]
  (let [last-line (when logfile (last (str/split-lines (proc/tail-file logfile 1))))]
    {:label       (str "PID " pid "  " command)
     :description (str "running " (format-duration (- now started))
                       (when logfile (str "  ·  " logfile))
                       (when (seq last-line) (str "  ·  " last-line)))
     :event       {:type :ext.process-manager/kill-selected
                   :room-id room-id
                   :pid pid}}))

(defn- cmd-processes
  "Open an interactive menu of tracked processes; Enter kills the selected one."
  [st {:keys [room-id]}]
  (let [procs (get-processes st room-id)]
    (if (empty? procs)
      {:state (update-in st [:rooms room-id :history] conj
                         {:kind :status :text "No tracked processes."})}
      (let [now   (js/Date.now)
            items (mapv #(process-menu-item now room-id %) procs)]
        {:state (assoc-in st [:rooms room-id :ui :menu]
                          {:id :processes :prompt "kill process> " :items items})}))))

(defn- kill-selected
  "Menu-selected a process → kill it and drop it from the tracked list."
  [st {:keys [room-id pid]}]
  (let [procs   (get-processes st room-id)
        command (some #(when (= pid (:pid %)) (:command %)) procs)
        killed  (kill-pid! pid)
        procs'  (vec (remove #(= pid (:pid %)) procs))]
    {:state (-> st
                (assoc-in [:rooms room-id :ext ext-id :processes] procs')
                (update-in [:rooms room-id :history] conj
                           {:kind :status
                            :text (if killed
                                    (str "Killed PID " pid (when command (str " — " command)))
                                    (str "PID " pid " already dead — removed from list"))}))}))

;; ── Log buffer ────────────────────────────────────────────────────────────────
;; A followed log is re-read on a timer and re-installed via :ui/buffer-set
;; (broadcast + mirrored like any buffer) whenever its tail or status changed.
;; The timer stops once the process exited or the buffer (or room) is gone.

(def ^:private log-lines 500)
(def ^:private follow-interval-ms 1500)

;; [room-id pid] → interval handle
(defonce ^:private followers (atom {}))

(defn- unfollow! [k]
  (when-let [t (get @followers k)]
    (js/clearInterval t)
    (swap! followers dissoc k)))

(defn- log-snapshot
  "The log's tail and the process's status. The `.exit` sidecar decides
   first: a child the clj worker hasn't reaped yet still answers kill 0."
  [{:keys [pid logfile]}]
  (let [exit (proc/read-exit-code (proc/exit-file-of logfile))]
    {:text     (proc/tail-file logfile log-lines)
     :running? (and (nil? exit) (proc/pid-alive? pid))
     :exit     exit}))

(defn- open-log
  "A client asked to view a tracked process's log. Only registry entries
   resolve, so a client can't name an arbitrary file."
  [st {:keys [room-id pid client-id]}]
  (when-let [p (reg/find-process st room-id pid)]
    {:effects [[:process-manager/follow-log
                (cond-> {:room-id room-id :process p}
                  client-id (assoc :client-id client-id))]]}))

(defn- follow-log-fx
  [{:keys [dispatch! get-state]} {:keys [room-id process client-id]}]
  (let [{:keys [pid command logfile]} process
        id    (reg/log-buffer-id pid)
        k     [room-id pid]
        seen  (atom nil)
        push! (fn []
                (let [snap (log-snapshot process)]
                  (when (not= snap @seen)
                    (reset! seen snap)
                    (dispatch! {:type :ui/buffer-set :room-id room-id :buffer-id id
                                :buffer (merge {:kind :process-log :title (str "$ " command)
                                                :pid pid :command command :logfile logfile}
                                               snap)}))
                  snap))]
    (unfollow! k)
    (push!)
    (dispatch! (cond-> {:type :ui/buffer-switch :room-id room-id :buffer-id id}
                 client-id (assoc :client-id client-id)))
    (when (:running? @seen)
      (swap! followers assoc k
             (js/setInterval
              (fn []
                (when (or (nil? (get-in (get-state) [:rooms room-id :ui :buffers id]))
                          (not (:running? (push!))))
                  (unfollow! k)))
              follow-interval-ms)))))

(def extension
  {:id               ext-id
   :init             {:room {:processes []}}
   :handlers         {:ext.process-manager/register       reg/register-process
                      :ext.process-manager/deregister     reg/deregister-process
                      :ext.process-manager/kill-selected  kill-selected
                      :process-manager/open-log           open-log
                      :room/close                         on-room-close}
   :fx               {:process-manager/follow-log follow-log-fx}
   ;; a client→server request, not room state
   :no-broadcast     #{:process-manager/open-log}
   :commands         [{:name "processes" :description "Pick a tracked process to kill" :handler cmd-processes}
                      {:name "ps"        :description "List tracked background processes" :handler cmd-ps}
                      {:name "kill"      :description "Kill a tracked process by index or PID" :handler cmd-kill}]})
