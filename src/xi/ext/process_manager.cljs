(ns xi.ext.process-manager
  "Track and control agent-started background processes.

   When the agent runs a bash command ending with `&` (backgrounded), this
   extension intercepts the tool call, spawns the process directly, records
   its PID, and returns a result telling the agent the PID. The user can
   then list (`/ps`) and kill (`/kill`) tracked processes.

   State is room-scoped:
     {:processes [{:pid N :command str :started ms-timestamp}]}"
  (:require [clojure.string :as str]
            [xi.core.state :as state]))

(def ^:private ext-id :process-manager)

;; ── Helpers ──────────────────────────────────────────────────────────────────

(defn- get-processes
  "Tracked processes for a room."
  [state room-id]
  (or (:processes (state/room-ext state room-id ext-id)) []))

(defn- pid-alive?
  "Check if a PID is still running."
  [pid]
  (try
    ;; signal 0 tests for existence without actually sending a signal
    (.kill js/process pid 0)
    true
    (catch :default _e false)))

(defn- kill-pid!
  "Kill a process by PID. Returns true if the signal was sent."
  [pid]
  (try
    (.kill js/process (- pid) "SIGTERM")
    true
    (catch :default _e
      (try
        (.kill js/process pid "SIGTERM")
        true
        (catch :default _e false)))))

(defn- strip-trailing-amp
  "Remove trailing `&` and whitespace from a command."
  [cmd]
  (str/trimr (str/replace cmd #"\s*&\s*$" "")))

(defn- background-command?
  "True when the command looks like it's starting a background process."
  [cmd]
  (boolean (re-find #"&\s*$" (str/trim (or cmd "")))))

;; ── Tool Gate ────────────────────────────────────────────────────────────────

(defn- spawn-background!
  "Spawn a command in the background, return its PID."
  [cmd cwd]
  (let [proc (js/Bun.spawn
              #js ["setsid" "bash" "-c" cmd]
              #js {:stdin  "ignore"
                   :stdout "ignore"
                   :stderr "ignore"
                   :env    (unchecked-get js/process "env")
                   :cwd    (or cwd (.cwd js/process))})]
    (.-pid proc)))

(defn- tool-gate
  "Intercept bash commands with `&`: spawn ourselves, track the PID, and
   return a synthetic result so the agent knows the PID."
  [tool-call {:keys [dispatch! get-state room-id cwd]}]
  (let [{:keys [name arguments]} tool-call
        lname (str/lower-case (or name ""))]
    (if (and (= lname "bash")
             (background-command? (:command arguments)))
      (let [cmd    (strip-trailing-amp (:command arguments))
            pid    (spawn-background! cmd cwd)
            now    (.now js/Date)
            entry  {:pid pid :command cmd :started now}]
        ;; Dispatch state update to record the process
        (dispatch! {:type      :ext.process-manager/register
                    :room-id   room-id
                    :process   entry})
        {:intercepted true
         :result {:content [{:type "text"
                             :text (str "Started background process (PID " pid "): " cmd)}]
                  :is-error false}})
      tool-call)))

;; ── Event Handlers ───────────────────────────────────────────────────────────

(defn- register-process
  "Add a tracked process to room state."
  [st {:keys [room-id process]}]
  (when (state/get-room st room-id)
    {:state (update-in st [:rooms room-id :ext ext-id :processes]
                       (fnil conj []) process)}))

(defn- on-room-close
  "Kill all tracked processes when a room is destroyed."
  [st {:keys [room-id]}]
  (let [procs (get-processes st room-id)]
    (doseq [{:keys [pid]} procs]
      (kill-pid! pid))
    nil))

;; ── Commands ─────────────────────────────────────────────────────────────────

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

(defn- cmd-ps
  "List tracked background processes with status."
  [st {:keys [room-id]}]
  (let [procs (get-processes st room-id)
        now   (.now js/Date)]
    (if (empty? procs)
      {:state (update-in st [:rooms room-id :history] conj
                         {:kind :status :text "No tracked processes."})}
      (let [lines (map-indexed
                   (fn [i {:keys [pid command started]}]
                     (let [alive (pid-alive? pid)
                           dur   (format-duration (- now started))]
                       (str "  " (inc i) ". [" (if alive "alive" "dead") "] "
                            "PID " pid " (" dur ") — " command)))
                   procs)]
        {:state (update-in st [:rooms room-id :history] conj
                           {:kind :status
                            :text (str "Tracked processes:\n"
                                       (str/join "\n" lines))})}))))

(defn- parse-kill-target
  "Parse the /kill argument — an index (1-based) or a PID."
  [arg procs]
  (when-let [n (parse-long arg)]
    (if (<= 1 n (count procs))
      ;; Looks like an index
      (nth procs (dec n))
      ;; Try as PID
      (some #(when (= n (:pid %)) %) procs))))

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

(def extension
  {:id         ext-id
   :init       {:room {:processes []}}
   :handlers   {:ext.process-manager/register register-process
                :room/close                   on-room-close}
   :tool-gate  tool-gate
   :commands   [{:name "ps"   :description "List tracked background processes" :handler cmd-ps}
                {:name "kill" :description "Kill a tracked process by index or PID" :handler cmd-kill}]})
