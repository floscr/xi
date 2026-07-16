(ns xi.ext.process-manager
  "Track and control agent-started background processes.

   The agent starts long-running processes (dev servers, watchers,
   `--watch` builds — anything that does not exit on its own) with the
   `start_process` tool instead of `bash`: the `bash` tool kills anything
   still running after its 30s timeout, whereas this extension spawns the
   command detached (via `setsid`), redirects its stdout+stderr to a log
   file, records the PID, and returns both so the agent can read the log
   and manage the process. `list_processes` and `stop_process` manage them;
   the user can also `/ps` and `/kill`.

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

;; Handles for awaiting a process's completion, kept process-local like the
;; log buffers. Each entry's :exited promise resolves to the process exit code;
;; wait_for_process races it against a timeout. Retained after natural exit (a
;; resolved promise + two strings) so a late wait still resolves; dropped on
;; kill / room-close.
(defonce ^:private proc-handles (atom {})) ;; pid -> {:exited <promise> :logfile str :command str}

;; Active `poll_until` loops. Each poll is tracked like a process under a
;; synthetic NEGATIVE pid (never collides with a real OS pid) so it shows up in
;; list_processes / /ps and is cancelable through the same stop_process / /kill
;; paths — plus ESC (turn-end cancels lingering polls). Value is the cancel fn.
(defonce ^:private poll-counter (atom 0))
(defonce ^:private poll-cancels (atom {})) ;; neg-pid -> (fn [] cancel!)

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

(defn- logfile-tail
  "Read the last `n` lines of a logfile off disk (async → Promise<string>).
   The logfile is written via `tee`, so it's the durable source of a
   process's output even after its in-memory buffer is gone."
  [logfile n]
  (-> (.text (js/Bun.file logfile))
      (.then (fn [text]
               (->> (str/split (str text) #"\n")
                    (remove str/blank?)
                    (take-last n)
                    (str/join "\n"))))
      (.catch (fn [_] ""))))

(defn- pid-alive?
  "Check if a PID is still running. A tracked poll (synthetic pid) counts as
   alive while it's registered."
  [pid]
  (if (contains? @poll-cancels pid)
    true
    (try
      ;; signal 0 tests for existence without actually sending a signal
      (.kill js/process pid 0)
      true
      (catch :default _e false))))

(defn- kill-pid!
  "Kill a process by PID and drop its log buffer + wait handle. Returns true
   if the signal was sent. A synthetic poll pid is canceled via its stored
   cancel fn instead of an OS signal."
  [pid]
  (drop-buffer! pid)
  (swap! proc-handles dissoc pid)
  (if-let [cancel! (get @poll-cancels pid)]
    (do (cancel!) true)
    (try
      (.kill js/process (- pid) "SIGTERM")
      true
      (catch :default _e
        (try
          (.kill js/process pid "SIGTERM")
          true
          (catch :default _e false))))))

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
    (pump-output! pid (.-stdout proc))
    (swap! proc-handles assoc pid {:exited (.-exited proc) :logfile logfile :command cmd})
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
                   "If this is a finite command (a build/test) you must finish before "
                   "continuing, call `wait_for_process` with target " pid " to block "
                   "until it exits — do NOT `sleep` in bash. If it's a long-lived "
                   "server/watcher, leave it running and just read its log when needed.\n"
                   "Manage with list_processes / stop_process."))}))

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
             (fn [i {:keys [pid command started logfile kind]}]
               (let [poll? (= kind :poll)
                     tail  (when-not poll? (recent-output pid 3))]
                 (str "  " (inc i) ". ["
                      (cond poll? "polling" (pid-alive? pid) "alive" :else "dead") "] "
                      (if poll? "poll" (str "PID " pid))
                      " (" (format-duration (- now started)) ") — " command
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

(def ^:private default-wait-ms 120000)

(defn- resolve-wait-pid
  "Resolve a wait/stop target (1-based index or PID) to a PID that has a
   live wait handle. Falls back to a raw PID lookup in `proc-handles` so a
   process that already deregistered from the tracked list (natural exit)
   can still be awaited."
  [target procs]
  (let [n   (parse-long (str/trim (str (or target ""))))
        via (parse-kill-target target procs)]
    (or (:pid via)
        (when (and n (contains? @proc-handles n)) n))))

(defn- wait-process-result!
  "Block until a tracked process exits (or `timeout-ms` elapses), then return
   an intercepted result with the exit code + a tail of its log. Returns a
   Promise; the tool gate awaits it. Not subject to bash's 30s timeout."
  [target timeout-ms st room-id]
  (let [procs  (get-processes st room-id)
        pid    (resolve-wait-pid target procs)
        handle (get @proc-handles pid)]
    (if (nil? handle)
      (js/Promise.resolve
       {:intercepted true
        :result (text-result
                 (str "No tracked process matching: " target
                      " — it may have already exited; read its log file instead."))})
      (let [tmo       (or timeout-ms default-wait-ms)
            timeout-p (js/Promise. (fn [res _] (js/setTimeout #(res ::timeout) tmo)))]
        (-> (js/Promise.race #js [(:exited handle) timeout-p])
            (.then
             (fn [outcome]
               (if (= outcome ::timeout)
                 (-> (logfile-tail (:logfile handle) 20)
                     (.then (fn [tail]
                              {:intercepted true
                               :result (text-result
                                        (str "PID " pid " still running after "
                                             (format-duration tmo)
                                             ". Call wait_for_process again to keep waiting."
                                             (when (seq tail) (str "\nRecent output:\n" tail))))})))
                 (-> (logfile-tail (:logfile handle) 50)
                     (.then (fn [tail]
                              {:intercepted true
                               :result (text-result
                                        (str "PID " pid " exited with code " outcome ".\n"
                                             "Output (last 50 lines of " (:logfile handle) "):\n"
                                             tail))}))))))))))) 

;; ── Poll until a condition holds ─────────────────────────────────────────────

(def ^:private default-poll-interval-ms 5000)
(def ^:private default-poll-timeout-ms 300000)
(def ^:private poll-output-cap 4000)

(defn- clip-output
  "Keep the last `poll-output-cap` chars of a command's output."
  [s]
  (let [s (str s)]
    (if (> (count s) poll-output-cap)
      (str "…[earlier output truncated]…\n" (subs s (- (count s) poll-output-cap)))
      s)))

(defn- run-once!
  "Run `cmd` once to completion, capturing merged stdout+stderr.
   Returns a Promise of {:output str :code int}."
  [cmd cwd]
  (let [proc (js/Bun.spawn
              #js ["setsid" "bash" "-c" cmd]
              #js {:stdin  "ignore"
                   :stdout "pipe"
                   :stderr "pipe"
                   :env    (unchecked-get js/process "env")
                   :cwd    (or cwd (.cwd js/process))})]
    (-> (js/Promise.all
         #js [(.text (.-stdout proc)) (.text (.-stderr proc)) (.-exited proc)])
        (.then (fn [rs]
                 {:output (str (aget rs 0) (aget rs 1))
                  :code   (aget rs 2)})))))

(defn- poll-condition-met?
  "Evaluate the stop condition against one attempt's result. `re` is the
   compiled pattern (only used by the match variants)."
  [until re {:keys [output code]}]
  (case until
    "stdout_matches"     (boolean (re-find re output))
    "stdout_not_matches" (not (re-find re output))
    (= 0 code)))

(defn- poll-until-result!
  "Run `cmd` every `interval-ms` until the `until` condition holds or
   `timeout-ms` elapses. Returns a Promise of an intercepted tool result.
   Not subject to bash's 30s timeout; resumable on deadline like
   wait_for_process. Registers the poll as a tracked process (synthetic
   negative pid) so it shows in list_processes / /ps and is cancelable via
   stop_process, /kill, and ESC (turn-end cancels lingering polls)."
  [cmd until re interval-ms timeout-ms cwd room-id dispatch!]
  (let [interval (or interval-ms default-poll-interval-ms)
        timeout  (or timeout-ms default-poll-timeout-ms)
        started  (.now js/Date)
        deadline (+ started timeout)
        poll-pid (- (swap! poll-counter inc))
        done?    (atom false)
        timer    (atom nil)]
    (js/Promise.
     (fn [resolve _]
       (let [finish (fn [result-map]
                      (when-not @done?
                        (reset! done? true)
                        (some-> @timer js/clearTimeout)
                        (swap! poll-cancels dissoc poll-pid)
                        (dispatch! {:type :ext.process-manager/deregister
                                    :room-id room-id :pid poll-pid})
                        (resolve result-map)))]
         ;; Register a cancel fn (used by stop_process / /kill / turn-end)
         ;; and a tracked-process entry so the poll is visible + stoppable.
         (swap! poll-cancels assoc poll-pid
                (fn []
                  (finish {:intercepted true
                           :result (text-result
                                    (str "poll_until cancelled after "
                                         (format-duration (- (.now js/Date) started))
                                         " — stopped by the user."))})))
         (dispatch! {:type :ext.process-manager/register
                     :room-id room-id
                     :process {:pid poll-pid :kind :poll :started started
                               :command (str "poll_until: " cmd)}})
         (letfn [(attempt [n]
                   (-> (run-once! cmd cwd)
                       (.then
                        (fn [res]
                          (when-not @done?
                            (let [elapsed (format-duration (- (.now js/Date) started))]
                              (cond
                                (poll-condition-met? until re res)
                                (finish {:intercepted true
                                         :result (text-result
                                                  (str "Condition met after " n
                                                       (if (= 1 n) " attempt (" " attempts (")
                                                       elapsed ").\n"
                                                       "Exit code: " (:code res) "\n"
                                                       "Output:\n" (clip-output (:output res))))})

                                (>= (.now js/Date) deadline)
                                (finish {:intercepted true
                                         :result (text-result
                                                  (str "Condition NOT met after " n
                                                       (if (= 1 n) " attempt / " " attempts / ")
                                                       elapsed ". Call poll_until again to keep waiting.\n"
                                                       "Last exit code: " (:code res) "\n"
                                                       "Last output:\n" (clip-output (:output res))))})

                                :else
                                (reset! timer (js/setTimeout #(attempt (inc n)) interval)))))))))]
           (attempt 1)))))))

;; ── Tool Gate ────────────────────────────────────────────────────────────────

(defn- tool-gate
  "Handle the process tools (they need dispatch!/get-state/room-id, which
   exec-fns don't receive — only the gate does), and intercept bash
   commands ending in `&` as a safety net."
  [tool-call {:keys [dispatch! get-state room-id cwd]}]
  (let [{:keys [name arguments]} tool-call
        lname (str/lower-case (or name ""))]
    (cond
      (= lname "start_process")
      (start-process-result! (:command arguments) cwd room-id dispatch!)

      (= lname "list_processes")
      {:intercepted true
       :result (text-result (format-process-list (get-processes (get-state) room-id)))}

      (= lname "stop_process")
      (stop-process-result! (:target arguments) (get-state) room-id dispatch!)

      (= lname "wait_for_process")
      (wait-process-result! (:target arguments) (:timeout_ms arguments) (get-state) room-id)

      (= lname "poll_until")
      (let [{:keys [command until pattern interval_ms timeout_ms]} arguments
            until (or until "exit_zero")]
        (cond
          (str/blank? command)
          {:intercepted true :result (text-result "poll_until requires a `command`.")}

          (not (#{"exit_zero" "stdout_matches" "stdout_not_matches"} until))
          {:intercepted true
           :result (text-result
                    (str "poll_until: unknown `until` " until
                         "; use exit_zero, stdout_matches, or stdout_not_matches."))}

          (and (not= until "exit_zero") (str/blank? pattern))
          {:intercepted true
           :result (text-result
                    (str "poll_until with until=" until " requires a `pattern` (regex)."))}

          :else
          (let [re (when (not= until "exit_zero")
                     (try (re-pattern pattern) (catch :default _ ::bad)))]
            (if (= re ::bad)
              {:intercepted true
               :result (text-result (str "poll_until: invalid regex pattern: " pattern))}
              (poll-until-result! command until re interval_ms timeout_ms cwd
                                  room-id dispatch!)))))

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
                                          :description "The command to run in the background, e.g. 'npm run dev'."}}
                   :required ["command"]}}
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
                   :required ["target"]}}
   {:name "wait_for_process"
    :description (str "Block until a background process started with "
                      "start_process finishes, then return its exit code and "
                      "the tail of its output. Use this — NOT `sleep` in bash — "
                      "when you need to wait for a finite long-running command "
                      "(a build, a slow test run) to complete before continuing. "
                      "Unlike bash it is not killed at 30s. If it returns "
                      "'still running', just call it again to keep waiting.")
    :input_schema {:type "object"
                   :properties {:target {:type "string"
                                         :description "The process PID, or its 1-based index from list_processes."}
                                :timeout_ms {:type "integer"
                                             :description "Max ms to wait before returning 'still running' (default 120000)."}}
                   :required ["target"]}}
   {:name "poll_until"
    :description (str "Run a command repeatedly on an interval until a "
                     "condition holds, then return its final output. Use this "
                     "— NOT `sleep N && cmd` in bash — to WAIT ON SOMETHING "
                     "EXTERNAL you did not start: a CI run to finish, a health "
                     "endpoint to come up, a log line to appear, a file to be "
                     "produced. It is not killed at 30s and is resumable: on "
                     "timeout it says 'call poll_until again'. (To wait on a "
                     "process YOU started with start_process, use "
                     "wait_for_process instead.) Conditions: `exit_zero` "
                     "(default) stops when the command exits 0; "
                     "`stdout_matches` / `stdout_not_matches` stop when the "
                     "combined stdout+stderr does / does not match `pattern` "
                     "(a regex). While polling it shows up in list_processes "
                     "and can be stopped early with stop_process (or the user "
                     "pressing ESC).")
    :input_schema {:type "object"
                   :properties {:command {:type "string"
                                          :description "Shell command run once per attempt."}
                                :until {:type "string"
                                        :enum ["exit_zero" "stdout_matches" "stdout_not_matches"]
                                        :description "Stop condition (default exit_zero)."}
                                :pattern {:type "string"
                                          :description "Regex tested against combined stdout+stderr; required for stdout_matches / stdout_not_matches."}
                                :interval_ms {:type "integer"
                                              :description "Delay between attempts in ms (default 5000)."}
                                :timeout_ms {:type "integer"
                                             :description "Overall deadline in ms before returning 'not met, call again' (default 300000)."}}
                   :required ["command"]}}])

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
       "When you need to WAIT for a finite long-running command (a build, a "
       "slow test run) to finish before continuing, call `wait_for_process` "
       "with its PID — it blocks until the process exits (not killed at 30s) "
       "and returns the exit code plus its output tail. NEVER `sleep` in `bash` "
       "to wait a process out — that just hits the same 30s timeout. For a "
       "process you don't need to wait on (a dev server you'll leave running), "
       "just move on and later read its log with `read` or call "
       "`list_processes`.\n"
       "When you need to WAIT ON SOMETHING EXTERNAL that you did NOT start — a "
       "CI run to complete, a server's health endpoint to come up, a log line "
       "to appear, a file to be produced — use the `poll_until` tool INSTEAD of "
       "`sleep N && cmd` in bash (which hits the 30s timeout and only checks "
       "once). It reruns a command every `interval_ms` until a condition holds "
       "(`exit_zero`, or `stdout_matches` / `stdout_not_matches` a regex), is "
       "not killed at 30s, and is resumable — if it reports the condition "
       "wasn't met, just call it again. A running `poll_until` appears in "
       "`list_processes` and can be stopped early with `stop_process` (or the "
       "user pressing ESC to abort the turn). Rule of thumb: `wait_for_process` "
       "for a process YOU started, `poll_until` for external state."))

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

(defn- cancel-room-polls
  "On turn-end (including ESC abort), cancel any polls still running for the
   room so their loop doesn't leak past the turn that started them."
  [st {:keys [room-id]}]
  (let [pids (->> (get-processes st room-id)
                  (filter #(= :poll (:kind %)))
                  (map :pid))]
    (when (seq pids)
      {:effects [[:ext.process-manager/cancel-polls {:pids pids}]]})))

(defn- cancel-polls-fx
  "Effect: run each poll's stored cancel fn (resolves its tool promise and
   deregisters it)."
  [_ {:keys [pids]}]
  (doseq [pid pids]
    (when-let [cancel! (get @poll-cancels pid)]
      (cancel!))))

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
                      :agent/turn-end                     cancel-room-polls
                      :room/close                         on-room-close}
   :fx               {:ext.process-manager/cancel-polls   cancel-polls-fx}
   :tool-gate        tool-gate
   :commands         [{:name "processes" :description "Pick a tracked process to kill" :handler cmd-processes}
                      {:name "ps"        :description "List tracked background processes" :handler cmd-ps}
                      {:name "kill"      :description "Kill a tracked process by index or PID" :handler cmd-kill}]})
