(ns xi.ext.process-manager
  "Track and control agent-started background processes.

   The agent starts long-running processes (dev servers, watchers,
   `--watch` builds — anything that does not exit on its own) with the
   `start_process` tool instead of `bash`: the `bash` tool kills anything
   still running after its 30s timeout, whereas this extension spawns the
   command detached (via `setsid`), redirects its stdout+stderr to a log
   file, records the PID, and returns both so the agent can read the log
   and manage the process. `start_process` takes an optional `directory`
   (so the agent need not `cd <dir> &&`). `wait_process` blocks until a
   tracked process exits and returns its exit code + recent output (for
   slow-but-finite commands like test suites, instead of `sleep`+tail).
   `list_processes` and `stop_process` manage them; the user can also
   `/ps` and `/kill`.

   A bare `bash` command ending with `&` is intercepted the same way as a
   safety net, so backgrounded shell commands are tracked too.

   State is room-scoped:
     {:processes [{:pid N :command str :started ms-timestamp :logfile str}]}"
  (:require [clojure.string :as str]
            [xi.core.state :as state]))

(def ^:private ext-id :process-manager)

;; ── Helpers ──────────────────────────────────────────────────────────────────

(defn- get-processes
  "Tracked processes for a room."
  [state room-id]
  (or (:processes (state/room-ext state room-id ext-id)) []))

;; ── In-memory log buffers ────────────────────────────────────────────────────
;; Each tracked process gets a bounded ring buffer of its most recent output
;; lines, captured live from the spawned pipe (see pump-output!). Kept process-
;; local (never in app state / over the wire) so it can't bloat client sync.

(def ^:private max-buffer-lines 500)

(defonce ^:private log-buffers (atom {})) ;; pid -> {:lines [str] :partial str}

;; Live Bun subprocess objects, kept process-local so `wait_process` can await
;; a process's exit and read its exit code. Dropped on kill / room-close; kept
;; after a natural exit so the exit code stays readable.
(defonce ^:private procs* (atom {})) ;; pid -> {:proc Bun-subprocess :entry {…}}

(defn- append-output!
  "Append decoded output text to a pid's buffer, splitting on newlines and
   capping to the most recent `max-buffer-lines` complete lines."
  [pid text]
  (swap! log-buffers update pid
         (fn [{:keys [lines partial] :or {lines [] partial ""}}]
           (let [parts    (str/split (str partial text) #"\n" -1)
                 complete (into lines (butlast parts))
                 capped   (if (> (count complete) max-buffer-lines)
                            (subvec (vec complete) (- (count complete) max-buffer-lines))
                            (vec complete))]
             {:lines capped :partial (last parts)}))))

(defn- drop-buffer!
  "Discard a pid's buffer (on kill/exit)."
  [pid]
  (swap! log-buffers dissoc pid))

(defn- recent-output
  "The last `n` buffered output lines for a pid (includes an in-flight
   partial line), newest last. Empty when nothing captured yet."
  [pid n]
  (let [{:keys [lines partial] :or {lines [] partial ""}} (get @log-buffers pid)
        all (cond-> lines (seq partial) (conj partial))]
    (vec (take-last n all))))

(defn- pid-alive?
  "Check if a PID is still running."
  [pid]
  (try
    ;; signal 0 tests for existence without actually sending a signal
    (.kill js/process pid 0)
    true
    (catch :default _e false)))

(defn- kill-pid!
  "Kill a process by PID and drop its log buffer. Returns true if the
   signal was sent."
  [pid]
  (drop-buffer! pid)
  (swap! procs* dissoc pid)
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

(defn- text-result
  "A non-error tool result carrying a single text block."
  [s]
  {:content [{:type "text" :text s}] :is-error false})

;; ── Spawning + tracking ──────────────────────────────────────────────────────

(defn- new-log-path
  "A fresh temp log-file path for a background process."
  []
  (str (or (aget js/process.env "TMPDIR") "/tmp")
       "/xi-proc-" (.now js/Date) "-" (rand-int 1000000) ".log"))

(defn- pump-output!
  "Drain a process's output ReadableStream into its in-memory log buffer.
   Runs asynchronously until the stream closes (process exit)."
  [pid stream]
  (let [reader  (.getReader stream)
        decoder (js/TextDecoder.)]
    (letfn [(step []
              (-> (.read reader)
                  (.then (fn [res]
                           (if (.-done res)
                             (.releaseLock reader)
                             (do (append-output! pid (.decode decoder (.-value res) #js {:stream true}))
                                 (step)))))
                  (.catch (fn [_] (try (.releaseLock reader) (catch :default _e nil))))))]
      (step))))

(defn- spawn-background!
  "Spawn a command detached, capturing stdout+stderr to both `logfile` (via
   `tee`, so the `read` tool works) and a live pipe (so we can buffer recent
   output in memory). Returns the Bun subprocess. The `{ … ;}` group makes
   the redirection cover the whole command list, and `setsid` gives it its
   own process group so `kill-pid!` can take down the children too."
  [cmd cwd logfile]
  (js/Bun.spawn
   #js ["setsid" "bash" "-c" (str "{ " cmd " ; } 2>&1 | tee " logfile)]
   #js {:stdin  "ignore"
        :stdout "pipe"
        :stderr "ignore"
        :env    (unchecked-get js/process "env")
        :cwd    (or cwd (.cwd js/process))}))

(defn- start-process-result!
  "Spawn `cmd` in the background, track the PID, stream its output into the
   log buffer, auto-deregister it when it exits, and return an intercepted
   tool result telling the agent the PID and log path."
  [cmd cwd room-id dispatch!]
  (let [logfile (new-log-path)
        proc    (spawn-background! cmd cwd logfile)
        pid     (.-pid proc)
        entry   {:pid pid :command cmd :started (.now js/Date) :logfile logfile}]
    (swap! procs* assoc pid {:proc proc :entry entry})
    (pump-output! pid (.-stdout proc))
    ;; When the process exits on its own, drop it from the tracked list so
    ;; the room can auto-close again (keep-alive? only counts live procs).
    (-> (.-exited proc)
        (.then (fn [_code]
                 (drop-buffer! pid)
                 (dispatch! {:type :ext.process-manager/deregister
                             :room-id room-id :pid pid}))))
    (dispatch! {:type    :ext.process-manager/register
                :room-id room-id
                :process entry})
    {:intercepted true
     :result (text-result
              (str "Started background process (PID " pid "): " cmd "\n"
                   "Logs: " logfile " (read this file with the `read` tool to see output)\n"
                   "Manage with list_processes / stop_process; call wait_process "
                   "to block until it exits and get its output."))}))

(defn- read-log-tail
  "Promise of the last `n` lines of a logfile (empty string if unreadable)."
  [logfile n]
  (-> (.text (js/Bun.file logfile))
      (.then (fn [txt] (str/join "\n" (take-last n (str/split-lines txt)))))
      (.catch (fn [_] ""))))


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
  "Render the tracked processes as a text block (shared by /ps and the
   list_processes tool)."
  [procs]
  (if (empty? procs)
    "No tracked processes."
    (let [now (.now js/Date)]
      (str "Tracked processes:\n"
           (str/join
            "\n"
            (map-indexed
             (fn [i {:keys [pid command started logfile]}]
               (let [tail (recent-output pid 3)]
                 (str "  " (inc i) ". [" (if (pid-alive? pid) "alive" "dead") "] "
                      "PID " pid " (" (format-duration (- now started)) ") — " command
                      (when logfile (str "\n       logs: " logfile))
                      (when (seq tail)
                        (str "\n       recent output:\n"
                             (str/join "\n" (map #(str "       | " %) tail)))))))
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

(defn- stop-process-result!
  "Kill a tracked process by PID/index and return an intercepted result."
  [target st room-id dispatch!]
  (let [procs (get-processes st room-id)]
    (if-let [{:keys [pid command]} (parse-kill-target target procs)]
      (let [killed (kill-pid! pid)]
        (dispatch! {:type :ext.process-manager/deregister :room-id room-id :pid pid})
        {:intercepted true
         :result (text-result
                  (if killed
                    (str "Killed PID " pid " — " command)
                    (str "PID " pid " already dead — removed from list.")))})
      {:intercepted true
       :result (text-result
                (str "No tracked process matching: " target
                     (when (empty? procs) " (none tracked)")))})))

;; ── Tool Gate ────────────────────────────────────────────────────────────────


(defn- wait-process-result!
  "Wait for a tracked process to exit (or until `timeout-ms` elapses), then
   return an intercepted result with its exit status and recent log output."
  [target st room-id timeout-ms]
  (let [procs (get-processes st room-id)
        entry (or (parse-kill-target target procs)
                  ;; A quickly-finished process is gone from room state but
                  ;; still recorded in procs* — resolve it by PID there.
                  (when-let [n (parse-long (str/trim (str (or target ""))))]
                    (:entry (get @procs* n))))]
    (if-not entry
      (js/Promise.resolve
       {:intercepted true
        :result (text-result (str "No tracked process matching: " target
                                  (when (empty? procs) " (none tracked)")))})
      (let [{:keys [pid command logfile]} entry
            ^js proc (:proc (get @procs* pid))
            timeout  (or timeout-ms 120000)]
        (-> (if (and proc (nil? (.-exitCode proc)))
              (js/Promise.race
               #js [(.-exited proc)
                    (js/Promise. (fn [res] (js/setTimeout #(res ::timeout) timeout)))])
              (js/Promise.resolve (some-> proc .-exitCode)))
            (.then
             (fn [outcome]
               (.then
                (read-log-tail logfile 40)
                (fn [tail]
                  (let [timed-out? (= outcome ::timeout)
                        header (if (and timed-out? (pid-alive? pid))
                                 (str "PID " pid " still running after "
                                      (format-duration timeout) " — " command)
                                 (str "PID " pid " exited"
                                      (when (number? outcome) (str " (exit code " outcome ")"))
                                      " — " command))]
                    {:intercepted true
                     :result (text-result
                              (str header "\nLogs: " logfile
                                   (when (seq tail) (str "\nRecent output:\n" tail))))}))))))))))

(defn- tool-gate
  "Handle the process tools (they need dispatch!/get-state/room-id, which
   exec-fns don't receive — only the gate does), and intercept bash
   commands ending in `&` as a safety net."
  [tool-call {:keys [dispatch! get-state room-id cwd]}]
  (let [{:keys [name arguments]} tool-call
        lname (str/lower-case (or name ""))]
    (cond
      (= lname "start_process")
      (start-process-result! (:command arguments) (or (:directory arguments) cwd) room-id dispatch!)

      (= lname "list_processes")
      {:intercepted true
       :result (text-result (format-process-list (get-processes (get-state) room-id)))}

      (= lname "wait_process")
      (let [t (:timeout arguments)]
        (wait-process-result! (:target arguments) (get-state) room-id
                              (when t (if (number? t) t (parse-long (str t))))))

      (= lname "stop_process")
      (stop-process-result! (:target arguments) (get-state) room-id dispatch!)

      (and (= lname "bash") (background-command? (:command arguments)))
      (start-process-result! (strip-trailing-amp (:command arguments)) cwd room-id dispatch!)

      :else tool-call)))

;; ── Tool Definitions ─────────────────────────────────────────────────────────

(def ^:private tool-defs
  [{:name "start_process"
    :description (str "Start a command in the background and track it. Use this "
                      "INSTEAD of the `bash` tool for ANYTHING that may run "
                      "longer than ~30 seconds — dev servers, watchers, "
                      "`--watch` builds, long-running or blocking commands like "
                      "`sleep 60`, slow builds, or slow test suites — because "
                      "`bash` kills any command still running after 30s. "
                      "start_process runs the command detached, captures "
                      "stdout+stderr to a log file, and returns its PID and log "
                      "path. Read that log file with the `read` tool to check "
                      "output. Reserve `bash` for commands that finish within a "
                      "few seconds.")
    :input_schema {:type "object"
                   :properties {:command {:type "string"
                                          :description "The command to run in the background, e.g. 'npm run dev'."}
                                :directory {:type "string"
                                            :description "Working directory to run the command in. Use this instead of prefixing the command with `cd <dir> &&`. Defaults to the session's current directory."}}
                   :required ["command"]}}
   {:name "wait_process"
    :description (str "Wait for a background process (started with start_process) "
                     "to finish, then return its exit status and recent output. "
                     "Use this for slow-but-finite commands (test suites, builds) "
                     "instead of `sleep`-ing in `bash` and tailing the log. Blocks "
                     "until the process exits or the timeout elapses.")
    :input_schema {:type "object"
                   :properties {:target {:type "string"
                                         :description "The process PID, or its 1-based index from list_processes."}
                                :timeout {:type "integer"
                                          :description "Max time to wait in ms (default 120000). If the process is still running when this elapses, returns its current output."}}
                   :required ["target"]}}
   {:name "list_processes"
    :description (str "List the background processes started with start_process "
                      "(or backgrounded bash commands), with each one's PID, "
                      "alive/dead status, uptime, log file, and command.")
    :input_schema {:type "object" :properties {} :required []}}
   {:name "stop_process"
    :description (str "Stop a background process started with start_process, by "
                      "its PID or its 1-based index from list_processes.")
    :input_schema {:type "object"
                   :properties {:target {:type "string"
                                         :description "The process PID, or its 1-based index from list_processes."}}
                   :required ["target"]}}])

(def ^:private system-prompt
  (str "## Long-running processes\n"
       "The `bash` tool kills any command still running after ~30 seconds. For "
       "ANY command that may take longer than that — dev servers, watchers, "
       "`--watch` builds, or long-running/blocking commands such as `sleep 60`, "
       "slow builds, or slow test suites — use the `start_process` tool INSTEAD "
       "of `bash`. It runs the command detached, captures its output to a log "
       "file, and returns the PID and log path. Read that log file with the "
       "`read` tool to check on it. Use `list_processes` to see what is running "
       "and `stop_process` to stop one. Reserve `bash` for commands that finish "
       "within a few seconds.\n"
       "For a slow-but-finite command (a test suite, a build) whose result you "
       "need before continuing, start it with `start_process` and then call "
       "`wait_process` with its PID/index — that blocks until it exits and "
       "returns the exit code plus recent output. NEVER `sleep` in `bash` and "
       "tail the log to wait for a process (that just hits the same 30s "
       "timeout). For a truly long-lived process (a dev server) you do not need "
       "to wait: tell the user its PID and log path and move on; when you later "
       "need its output, read the log file with `read` or call `list_processes`.\n"
       "To run a command in another directory, pass `start_process`'s "
       "`directory` argument instead of prefixing the command with `cd <dir> &&`."))

;; ── Event Handlers ───────────────────────────────────────────────────────────

(defn- register-process
  "Add a tracked process to room state."
  [st {:keys [room-id process]}]
  (when (state/get-room st room-id)
    {:state (update-in st [:rooms room-id :ext ext-id :processes]
                       (fnil conj []) process)}))

(defn- deregister-process
  "Remove a tracked process (by PID) from room state."
  [st {:keys [room-id pid]}]
  (when (state/get-room st room-id)
    {:state (update-in st [:rooms room-id :ext ext-id :processes]
                       (fn [ps] (vec (remove #(= pid (:pid %)) ps))))}))

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
  (let [last-line (last (recent-output pid 1))]
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

(def extension
  {:id               ext-id
   :init             {:room {:processes []}}
   :system-prompt    system-prompt
   :tool-definitions tool-defs
   :handlers         {:ext.process-manager/register       register-process
                      :ext.process-manager/deregister     deregister-process
                      :ext.process-manager/kill-selected  kill-selected
                      :room/close                         on-room-close}
   :tool-gate        tool-gate
   :commands         [{:name "processes" :description "Pick a tracked process to kill" :handler cmd-processes}
                      {:name "ps"        :description "List tracked background processes" :handler cmd-ps}
                      {:name "kill"      :description "Kill a tracked process by index or PID" :handler cmd-kill}]})
