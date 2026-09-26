(ns xi.ext.clj-process
  "Background processes for the clj sandbox — the `process` SCI namespace.

   Replaces the old standalone start_process / wait_for_process / poll_until
   tools: those took raw shell-command strings that escaped the permission
   gate. Here the commands live inside clj code, so `gate-clj` scans the
   literal strings (sudo / remote / server-control / guarded / CLI approval)
   and injects the approved set as `:_allowed-bg`; at runtime `start!` /
   `poll-until` refuse any command not in that set, so dynamically built
   strings can't bypass approval.

   Runs on the clj worker thread (see xi.ext.clj). The worker's SCI evals are
   synchronous, so `wait` / `poll-until` block the worker with an abortable
   Atomics.wait sleep — the main thread flips the per-eval abort flag on
   turn-end (ESC), which wakes the sleep and throws.

   Spawned processes are detached (own process group, output to a temp
   logfile, exit code to a `.exit` sidecar), so liveness (`kill pid 0`),
   outcome (sidecar), and output (logfile tail) are all observable without a
   live handle. Register/deregister events are mirrored to the main thread
   via parentPort so the process-manager extension's room state (/ps, /kill,
   room keep-alive) stays accurate."
  (:require [clojure.string :as str]
            ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:worker_threads" :as wt]))

;; ── Sleeping ─────────────────────────────────────────────────────────────────

(defn blocking-sleep
  "Synchronously block for `ms` milliseconds. Safe because clj eval runs in a
   dedicated worker_threads worker, never the server's main event loop."
  [ms]
  (let [ms (max 0 (or ms 0))]
    (if (and (exists? js/Bun) (fn? (.-sleepSync js/Bun)))
      (js/Bun.sleepSync ms)
      (js/Atomics.wait (js/Int32Array. (js/SharedArrayBuffer. 4)) 0 0 ms)))
  nil)

(defn sleep-abortable
  "Block for `ms` milliseconds, waking early when the eval's abort flag (an
   Int32Array over a SharedArrayBuffer, stored as :abort-arr in the runtime
   opts) is flipped by the main thread on turn-end — then throw so the
   blocked eval unwinds instead of outliving its turn."
  [opts ms]
  (let [^js arr (:abort-arr @opts)]
    (if-not arr
      (blocking-sleep ms)
      (do (js/Atomics.wait arr 0 0 (max 0 (or ms 0)))
          (when-not (zero? (js/Atomics.load arr 0))
            (throw (ex-info "clj: aborted (turn ended)" {:aborted true})))
          nil))))

;; ── Registry (worker-local) ──────────────────────────────────────────────────
;; pid → {:pid :command :started :logfile :exit-file :room-id}. Entries stay
;; after natural exit (so a late wait/output still works) and are dropped on
;; an explicit (process/stop pid).

(defonce ^:private procs (atom {}))

(defn- post-event!
  "Mirror a register/deregister to the main thread, which translates the room
   key back to a room-id and dispatches the process-manager event (room state
   drives /ps, /kill and room keep-alive)."
  [event {:keys [room-key pid command started logfile]}]
  (when-let [port wt/parentPort]
    (.postMessage port #js {:processEvent event
                            :roomKey      (str room-key)
                            :pid          pid
                            :command      command
                            :started      started
                            :logfile      logfile})))

(defn- room-entries [opts]
  (let [rid (:room-id @opts)]
    (->> (vals @procs)
         (filter #(= rid (:room-id %)))
         (sort-by :started))))

(defn- entry-of
  "Look up a tracked process; only registry pids are addressable, so
   process/stop can never signal an arbitrary OS pid."
  [pid]
  (or (get @procs pid)
      (throw (ex-info (str "process: unknown pid " pid
                           " — only pids returned by (process/start …) work")
                      {:pid pid}))))

;; ── Files ────────────────────────────────────────────────────────────────────

(defn- new-log-path []
  (str (or (aget js/process.env "TMPDIR") (os/tmpdir) "/tmp")
       "/xi-proc-" (.now js/Date) "-" (rand-int 1000000) ".log"))

(defn tail-file
  "Last `n` non-blank lines of a file (sync; reads at most the trailing 64KB).
   \"\" when the file is missing or empty. Also used by the process-manager
   extension for /ps output."
  [path n]
  (try
    (let [size  (.-size (fs/statSync path))
          start (max 0 (- size 65536))
          len   (- size start)
          buf   (js/Buffer.alloc len)
          fd    (fs/openSync path "r")]
      (try (fs/readSync fd buf 0 len start)
           (finally (fs/closeSync fd)))
      (->> (str/split (.toString buf "utf8") #"\n")
           (remove str/blank?)
           (take-last (or n 50))
           (str/join "\n")))
    (catch :default _ "")))

(defn- read-exit-code
  "Exit code from the `.exit` sidecar, or nil while the process runs (or if
   it was killed before the sidecar was written)."
  [exit-file]
  (try (parse-long (str/trim (fs/readFileSync exit-file "utf8")))
       (catch :default _ nil)))

;; ── Spawning ─────────────────────────────────────────────────────────────────

(defn pid-alive? [pid]
  (try (js/process.kill pid 0) true
       (catch :default _ false)))

(defn- kill-pid!
  "SIGTERM the process group (detached spawn → the pid is the group leader),
   falling back to the single pid. True when a signal was delivered."
  [pid]
  (try (js/process.kill (- pid) "SIGTERM") true
       (catch :default _
         (try (js/process.kill pid "SIGTERM") true
              (catch :default _ false)))))

(defn- spawn-detached!
  "Spawn `cmd` via bash in its own session/process group, stdout+stderr to
   `logfile`, exit code to `exit-file`. Returns the child (unref'd)."
  [cmd cwd logfile exit-file]
  (let [script (str "{ " cmd "\n} > " logfile " 2>&1; echo $? > " exit-file)
        child  (cp/spawn "bash" #js ["-c" script]
                         #js {:detached true
                              :stdio    "ignore"
                              :cwd      (or cwd (.cwd js/process))
                              :env      (unchecked-get js/process "env")})]
    (.unref child)
    child))

(defn- require-approved!
  "Enforce the gate's approval at runtime: the exact command string must be in
   the injected :_allowed-bg set, so a dynamically built command can't run."
  [opts cmd]
  (when-not (contains? (:allowed-bg @opts) (str cmd))
    (throw (ex-info (str "process: the command must be a literal string in this "
                         "eval, approved by the tool gate — got " (pr-str cmd))
                    {:command (str cmd)}))))

;; ── SCI-facing ops ───────────────────────────────────────────────────────────

(defn- start!
  "Spawn `cmd` detached in the background → {:pid N :log path}.
   `dir` (optional, from a leading {:dir …} opts map) overrides the cwd."
  [opts cmd dir]
  (require-approved! opts cmd)
  (let [cmd'      (str/trimr (str/replace (str cmd) #"\s*&\s*$" ""))
        logfile   (new-log-path)
        exit-file (str logfile ".exit")
        child     (spawn-detached! cmd' (or dir (:cwd @opts)) logfile exit-file)
        pid       (.-pid child)
        started   (.now js/Date)
        rid       (:room-id @opts)
        entry     {:pid pid :command cmd' :started started
                   :logfile logfile :exit-file exit-file :room-id rid}]
    (swap! procs assoc pid entry)
    ;; Fires once the worker is back on its event loop; keeps the main-thread
    ;; room state honest so an exited process no longer keeps the room alive.
    (.on child "exit"
         (fn [_code _sig]
           (post-event! "deregister" {:room-key rid :pid pid})))
    (post-event! "register" {:room-key rid :pid pid :command cmd'
                             :started started :logfile logfile})
    {:pid pid :log logfile}))

(defn- stop! [opts pid]
  (let [{:keys [command room-id]} (entry-of pid)
        killed? (and (pid-alive? pid) (kill-pid! pid))]
    (swap! procs dissoc pid)
    (post-event! "deregister" {:room-key room-id :pid pid})
    {:pid pid :killed? (boolean killed?) :command command}))

(defn- list-procs [opts]
  (mapv (fn [{:keys [pid command started logfile exit-file]}]
          (let [exit   (read-exit-code exit-file)
                alive? (and (nil? exit) (pid-alive? pid))]
            (cond-> {:pid pid :command command :alive? alive?
                     :uptime-ms (- (.now js/Date) started) :log logfile}
              (not alive?) (assoc :exit exit))))
        (room-entries opts)))

(defn- output [_opts pid & [n]]
  (tail-file (:logfile (entry-of pid)) (or n 50)))

(def ^:private default-wait-ms 120000)

(defn- wait
  "Block until the process exits (or `timeout-ms`, default 120s) →
   {:status :exited :exit N :output …} | {:status :running :note …}.
   The `.exit` sidecar is the primary exit signal: while this loop blocks the
   worker, its event loop can't reap a dead child, so the pid lingers as a
   zombie and `kill pid 0` would keep succeeding. The sidecar is written by
   the bash script itself, so it's reliable even then; the pid-alive? check
   only catches processes killed before the sidecar could be written."
  [opts pid & [timeout-ms]]
  (let [{:keys [logfile exit-file]} (entry-of pid)
        deadline (+ (.now js/Date) (or timeout-ms default-wait-ms))]
    (loop []
      (let [exit (read-exit-code exit-file)]
        (cond
          (or (some? exit) (not (pid-alive? pid)))
          {:status :exited :pid pid :exit exit
           :output (tail-file logfile 50)}

          (>= (.now js/Date) deadline)
          {:status :running :pid pid
           :note (str "still running after " (or timeout-ms default-wait-ms)
                      "ms — call (process/wait " pid ") again to keep waiting")
           :output (tail-file logfile 20)}

          :else (do (sleep-abortable opts 300) (recur)))))))

(def ^:private default-poll-interval-ms 5000)
(def ^:private default-poll-timeout-ms 120000)
(def ^:private poll-output-cap 4000)

(defn- run-once!
  "One poll attempt: run `cmd` via bash, merged stdout+stderr →
   {:exit N :output str}."
  [opts cmd dir]
  (let [^js res (cp/spawnSync "bash" #js ["-c" (str cmd)]
                              #js {:cwd (or dir (:cwd @opts) (.cwd js/process))
                                   :encoding "utf8"
                                   :timeout 30000
                                   :env (unchecked-get js/process "env")})
        out (str (.-stdout res) (.-stderr res))]
    {:exit   (if (some? (.-status res)) (.-status res) -1)
     :output (if (> (count out) poll-output-cap)
               (str "…" (subs out (- (count out) poll-output-cap)))
               out)}))

(defn- poll-met? [until pattern {:keys [exit output]}]
  (case until
    :stdout-matches     (boolean (re-find (re-pattern pattern) output))
    :stdout-not-matches (not (re-find (re-pattern pattern) output))
    (zero? exit)))

(defn- poll-until
  "Rerun `cmd` every :interval-ms until the :until condition holds
   (:exit-zero default | :stdout-matches | :stdout-not-matches + :pattern) or
   :timeout-ms elapses → {:met? bool :attempts N :exit N :output str}."
  [opts cmd dir & [{:keys [until pattern interval-ms timeout-ms]}]]
  (require-approved! opts cmd)
  (when (and (contains? #{:stdout-matches :stdout-not-matches} until)
             (not (string? pattern)))
    (throw (ex-info "process/poll-until: :pattern (string regex) is required for stdout conditions" {})))
  (let [deadline (+ (.now js/Date) (or timeout-ms default-poll-timeout-ms))]
    (loop [attempts 1]
      (let [res  (run-once! opts cmd dir)
            met? (poll-met? (or until :exit-zero) pattern res)]
        (cond
          met?
          (assoc res :met? true :attempts attempts)

          (>= (.now js/Date) deadline)
          (assoc res :met? false :attempts attempts
                 :note "timed out — call process/poll-until again to keep polling")

          :else
          (do (sleep-abortable opts (or interval-ms default-poll-interval-ms))
              (recur (inc attempts))))))))

(defn sci-namespace
  "The `process` namespace injected into the SCI ctx (see make-ctx). `opts` is
   the room runtime's opts atom — carries :allowed-bg, :cwd, :room-id and
   :abort-arr for the current eval. `resolve-dir` resolves + gates the :dir of
   an optional bb-style leading opts map on start/poll-until, e.g.
   (process/start {:dir \"sub/project\"} \"bb build\")."
  [opts resolve-dir]
  (let [split (fn [args]
                (if (map? (first args))
                  [(some-> (:dir (first args)) resolve-dir) (rest args)]
                  [nil args]))]
    {'start      (fn [& args]
                   (let [[dir [cmd]] (split args)]
                     (start! opts cmd dir)))
     'stop       (fn [pid] (stop! opts pid))
     'wait       (fn [pid & [timeout-ms]] (wait opts pid timeout-ms))
     'list       (fn [] (list-procs opts))
     'output     (fn [pid & [n]] (output opts pid n))
     'poll-until (fn [& args]
                   (let [[dir [cmd opt-map]] (split args)]
                     (poll-until opts cmd dir opt-map)))}))
