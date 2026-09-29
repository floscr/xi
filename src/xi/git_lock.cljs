(ns xi.git-lock
  "Cross-room git staging lock.

   Rooms working in the same repo share one git index, so one room's
   `git add` + another room's `git commit` bundles unrelated work. The lock
   serializes index-mutating git ops: the first room to touch the index holds
   a lease until the index is clean again (it committed, reset, or unstaged);
   every other room's index-mutating op waits. Read-only git (status, diff,
   log, …) never waits.

   The lease is a file, `<git-dir>/xi-staging.lock` (JSON), so it is shared
   by every Xi process on the machine (:7474, :7475, standalone TUIs) and is
   per-worktree (each worktree has its own git dir + index). The owner is
   {:pid :room} — a room key alone isn't unique across processes.

   A lease is stale (stealable) when its process is dead, or when the index
   has had nothing staged for STALE_CLEAN_MS (the holder committed/unstaged
   outside a tracked op, e.g. the user did it in a terminal).

   Pure classification (parse-argv, locking?, broad-add?, …) up top; the
   impure edge (git + lock file, sync) below — sync so the clj worker thread
   can call it too; only wait-acquire! is async (main thread)."
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:path" :as node-path]
            [clojure.string :as str]
            [xi.util :as util]))

;; ── Classification (pure) ────────────────────────────────────────────────────

(def ^:private VALUE_GLOBALS
  "Global git options that take a separate value argument."
  #{"-C" "-c" "--git-dir" "--work-tree" "--namespace"})

(defn parse-argv
  "Split git argv (without the leading \"git\") into {:dir :sub :args}: the
   effective -C directory (nil when absent; successive -C's nest like git's),
   the subcommand, and the args after it."
  [argv]
  (loop [[a & more] (map str argv)
         dir nil]
    (cond
      (nil? a) {:dir dir :sub nil :args []}
      (= a "-C") (let [d (first more)]
                   (recur (rest more)
                          (if (and dir d (not (node-path/isAbsolute d)))
                            (node-path/join dir d)
                            d)))
      (contains? VALUE_GLOBALS a) (recur (rest more) dir)
      (str/starts-with? a "-") (recur more dir)
      :else {:dir dir :sub a :args (vec more)})))

(def ^:private NON_LOCKING
  "Subcommands that never touch the index, HEAD or the working tree — they
   run freely while another room holds the lock. Anything not listed here
   locks (safe default for unknown/new subcommands)."
  #{"status" "diff" "log" "show" "blame" "annotate" "rev-parse" "ls-files"
    "ls-tree" "ls-remote" "cat-file" "shortlog" "describe" "grep" "reflog"
    "merge-base" "rev-list" "name-rev" "show-ref" "for-each-ref"
    "check-ignore" "check-attr" "help" "version" "var" "count-objects"
    "whatchanged" "range-diff" "format-patch" "archive" "difftool"
    "fetch" "push" "remote" "config" "branch" "tag" "worktree"})

(defn locking?
  "True when a parsed git op mutates the index, HEAD or working tree."
  [{:keys [sub args]}]
  (cond
    (nil? sub)                  false
    (contains? NON_LOCKING sub) false
    (= "stash" sub)             (not (contains? #{"list" "show"} (first args)))
    :else                       true))

(def ^:private BROAD_PATHSPECS #{"." "./" "*" ":/" ":/." ":."})

(defn broad-add?
  "True when a parsed git op stages files wholesale rather than by explicit
   path: `add -A/--all/-u/--update/.`, `commit -a/--all`."
  [{:keys [sub args]}]
  (case sub
    "add"    (boolean (some #(or (contains? BROAD_PATHSPECS %)
                                 (contains? #{"--all" "--update"} %)
                                 (re-matches #"-[^-]*[Au][^-]*" %))
                            args))
    "commit" (boolean (some #(or (= "--all" %)
                                 (re-matches #"-[^-]*a[^-]*" %))
                            args))
    false))

(defn shell-git-argvs
  "Git argvs (without \"git\") in a shell command string. Naive split on
   && || ; | and newlines + whitespace — good enough to classify, not to run."
  [command]
  (->> (str/split (str command) #"&&|\|\||[;|\n]")
       (map #(remove str/blank? (str/split (str/trim %) #"\s+")))
       (keep (fn [[bin & args]] (when (= "git" bin) (vec args))))))

(defn tool-call-argvs
  "Git argvs (without \"git\") a tool call will run, for the tools that run
   git directly. The clj tool is gated at runtime inside its worker instead."
  [{:keys [name arguments]}]
  (let [files (map str (:files arguments))]
    (case (some-> name util/strip-mcp-prefix str/lower-case)
      "git_stage_hunks" [(into ["add" "--"] files)]
      "git_commit"      (cond-> []
                          (seq files) (conj (into ["add" "--"] files))
                          :always     (conj ["commit"]))
      "bash"            (vec (shell-git-argvs (:command arguments)))
      nil)))

(defn op-cwd
  "Directory a parsed op runs in: its -C dir resolved against `cwd`."
  [cwd {:keys [dir]}]
  (if dir (node-path/resolve cwd dir) cwd))

(def STALE_CLEAN_MS
  "A lease whose index has had nothing staged for this long is stale — the
   holder finished (or the user unstaged/committed by hand) without a tracked
   release. Long enough to cover the gap between acquiring and staging."
  60000)

(defn stale?
  "Pure staleness check for a lock map. ctx: {:now ms :staged [paths]
   :alive? (fn [pid] → bool)}."
  [lock {:keys [now staged alive?]}]
  (or (not (alive? (:pid lock)))
      (and (empty? staged)
           (> (- now (or (:touched-at lock) 0)) STALE_CLEAN_MS))))

(defn same-owner? [lock owner]
  (and (some? lock)
       (= (:pid lock) (:pid owner))
       (= (str (:room lock)) (str (:room owner)))))

(defn foreign-sweep
  "Files a broad add would sweep up that other rooms edited. `dirty` is a set
   of absolute dirty paths; `others` is [{:label :files [abs-path]}]. Returns
   [{:label :files}] for rooms with at least one dirty edited file."
  [dirty others]
  (->> others
       (keep (fn [{:keys [label files]}]
               (when-let [hit (seq (filter #(contains? dirty %) files))]
                 {:label label :files (vec (distinct hit))})))
       vec))

(defn describe-holder
  "Human label for a lock holder."
  [lock]
  (str "\"" (or (not-empty (:label lock)) (:room lock) "?") "\""
       (when (not= (:pid lock) js/process.pid)
         (str " (another Xi process, pid " (:pid lock) ")"))))

;; ── Impure edge: git + lock file (sync) ──────────────────────────────────────

(def LOCK_NAME "xi-staging.lock")

(defn- git-out
  "Run git synchronously in `cwd` → {:ok? bool :out str}."
  [cwd & args]
  (let [r (cp/spawnSync "git" (clj->js args) #js {:cwd cwd :encoding "utf8"})]
    {:ok? (= 0 (.-status r)) :out (str (.-stdout r))}))

(defn git-dir
  "Absolute git dir for `cwd` (per-worktree), or nil outside a repo."
  [cwd]
  (let [{:keys [ok? out]} (git-out cwd "rev-parse" "--absolute-git-dir")]
    (when ok? (not-empty (str/trim out)))))

(defn toplevel [cwd]
  (let [{:keys [ok? out]} (git-out cwd "rev-parse" "--show-toplevel")]
    (when ok? (not-empty (str/trim out)))))

(defn staged-files
  "Paths currently staged in the index (relative to the repo root)."
  [cwd]
  (let [{:keys [ok? out]} (git-out cwd "diff" "--cached" "--name-only")]
    (if ok? (vec (remove str/blank? (str/split-lines out))) [])))

(defn dirty-files
  "Absolute paths of every changed file (staged, unstaged, untracked) in the
   repo containing `cwd`."
  [cwd]
  (if-let [top (toplevel cwd)]
    (let [{:keys [ok? out]} (git-out top "status" "--porcelain" "--untracked-files=all")]
      (if ok?
        (->> (str/split-lines out)
             (remove str/blank?)
             (map #(let [p (subs % 3)]
                     (node-path/join top (last (str/split p #" -> ")))))
             set)
        #{}))
    #{}))

(defn- lock-path [gd] (node-path/join gd LOCK_NAME))

(defn- read-lock [path]
  (try
    (js->clj (js/JSON.parse (fs/readFileSync path "utf8")) :keywordize-keys true)
    (catch :default _ nil)))

(defn- write-lock! [path lock]
  (fs/writeFileSync path (js/JSON.stringify (clj->js lock))))

(defn- create-lock!
  "Atomically create the lock file; false when it already exists."
  [path lock]
  (try
    (let [fd (fs/openSync path "wx")]
      (fs/writeSync fd (js/JSON.stringify (clj->js lock)))
      (fs/closeSync fd)
      true)
    (catch :default _ false)))

(defn- rm-lock! [path]
  (try (fs/unlinkSync path) (catch :default _ nil)))

(defn- pid-alive? [pid]
  (try (js/process.kill pid 0) true
       (catch :default e (= "EPERM" (.-code e)))))

(defn holder
  "The current lock map for the repo at `cwd`, or nil."
  [cwd]
  (some-> (git-dir cwd) lock-path read-lock))

(defn try-acquire!
  "One acquisition attempt for `owner` ({:pid :room :label}) →
   {:status :acquired|:busy|:no-repo, :holder lock, :staged [..], :fresh? bool}.
   :fresh? marks a newly created lease (vs. re-entering one we hold)."
  [cwd owner]
  (if-let [gd (git-dir cwd)]
    (let [path   (lock-path gd)
          now    (js/Date.now)
          staged (staged-files cwd)
          lock   (read-lock path)]
      (cond
        (same-owner? lock owner)
        (do (write-lock! path (assoc lock :touched-at now))
            {:status :acquired :staged staged :fresh? false})

        (and lock (not (stale? lock {:now now :staged staged :alive? pid-alive?})))
        {:status :busy :holder lock :staged staged}

        :else
        (do (when (fs/existsSync path)
              ;; stale or corrupt — re-read so we only clear what we judged
              (when (= lock (read-lock path)) (rm-lock! path)))
            (if (create-lock! path (assoc owner :acquired-at now :touched-at now))
              {:status :acquired :staged staged :fresh? true}
              {:status :busy :holder (read-lock path) :staged staged}))))
    {:status :no-repo}))

(defn release!
  "Drop `owner`'s lease on the repo at `cwd`. True when released."
  [cwd owner]
  (when-let [gd (git-dir cwd)]
    (let [path (lock-path gd)]
      (when (same-owner? (read-lock path) owner)
        (rm-lock! path)
        true))))

(defn settle!
  "Release `owner`'s lease when the index is clean (after a commit, reset,
   restore --staged, or at turn end). Keeps it while files are staged. True
   when released."
  [cwd owner]
  (when-let [gd (git-dir cwd)]
    (let [path (lock-path gd)]
      (when (and (same-owner? (read-lock path) owner)
                 (empty? (staged-files cwd)))
        (rm-lock! path)
        true))))

(defn force-release!
  "Drop whatever lease exists on the repo at `cwd`. Returns the dropped lock."
  [cwd]
  (when-let [gd (git-dir cwd)]
    (let [path (lock-path gd)
          lock (read-lock path)]
      (when (fs/existsSync path) (rm-lock! path))
      lock)))

;; ── Waiting (async, main thread) ─────────────────────────────────────────────

(def ^:private POLL_MS 2000)

(defn wait-acquire!
  "Poll try-acquire! until `owner` holds the lease.
   opts: {:cwd :owner :timeout-ms :cancelled? (fn [] → bool)
          :on-wait (fn [holder staged]) — called once, on the first busy poll}
   → Promise<{:status :acquired|:timeout|:cancelled|:no-repo
              :waited-ms :holder :staged :fresh?}>"
  [{:keys [cwd owner timeout-ms cancelled? on-wait]}]
  (let [start (js/Date.now)]
    (js/Promise.
     (fn [resolve _]
       (letfn [(attempt [notified?]
                 (let [{:keys [status holder] :as r} (try-acquire! cwd owner)
                       waited (- (js/Date.now) start)
                       r      (assoc r :waited-ms waited)]
                   (cond
                     (not= :busy status)          (resolve r)
                     (and cancelled? (cancelled?)) (resolve (assoc r :status :cancelled))
                     (>= waited timeout-ms)       (resolve (assoc r :status :timeout))
                     :else
                     (do (when (and on-wait (not notified?))
                           (on-wait holder (:staged r)))
                         (js/setTimeout #(attempt true) POLL_MS)))))]
         (attempt false))))))
