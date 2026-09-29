(ns xi.git-lock
  "Cross-room git staging lock — the `git-index` hold (see xi.holds).

   Rooms working in the same repo share one git index, so one room's
   `git add` + another room's `git commit` bundles unrelated work. The hold
   serializes index-mutating git ops: the first room to touch the index holds
   it until the index is clean again (it committed, reset, or unstaged);
   every other room's index-mutating op waits. Read-only git (status, diff,
   log, …) never waits.

   The lease lives at `<git-dir>/xi-staging.lock`, so it is per-worktree
   (each worktree has its own git dir + index); the hold's key is the
   worktree's toplevel. Beyond a dead owner, a lease is stale when the index
   has had nothing staged for STALE_CLEAN_MS (the holder committed/unstaged
   outside a tracked op, e.g. the user did it in a terminal).

   Broad adds (`add -A/./-u`, `commit -a`) that would sweep up files another
   room edited are refused outright — the hold's `:refuse` hook.

   Pure classification (parse-argv, locking?, broad-add?, …) up top; the git
   edge (sync, so the clj worker thread can call it too) and the hold below."
  (:require ["node:child_process" :as cp]
            ["node:path" :as node-path]
            [clojure.string :as str]
            [xi.fx :as fx]
            [xi.holds.lease :as lease]
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
   git directly. The clj tool is held at runtime inside its worker instead."
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

(defn stale-clean?
  "Git-specific staleness (on top of the lease's dead-pid check): nothing
   staged for STALE_CLEAN_MS. ctx: {:now ms :staged [paths]}."
  [lease {:keys [now staged]}]
  (and (empty? staged)
       (> (- now (or (:touched-at lease) 0)) STALE_CLEAN_MS)))

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

;; ── Git edge (sync) ──────────────────────────────────────────────────────────

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

(defn lease-path
  "The lease file for the worktree at `top`, or nil outside a repo."
  [top]
  (some-> (git-dir top) (node-path/join LOCK_NAME)))

;; ── The git-index hold ───────────────────────────────────────────────────────

(def ^:private DEFAULT_WAIT_SECS 600)

(defn- wait-ms []
  (let [v (js/parseInt (aget js/process.env "XI_GIT_LOCK_WAIT_SECS") 10)]
    (* 1000 (if (js/isNaN v) DEFAULT_WAIT_SECS v))))

(defn- staged-summary [staged]
  (when (seq staged)
    (str " (staged: " (str/join ", " (take 5 staged))
         (when (> (count staged) 5) (str ", +" (- (count staged) 5) " more"))
         ")")))

(defn- other-room-edits
  "[{:label :files [abs]}] for every other room in this process."
  [st room-id]
  (->> (:rooms st)
       (keep (fn [[rid room]]
               (when (and (not= rid room-id) (:cwd room))
                 {:label (lease/room-label room)
                  :files (mapv #(node-path/resolve (:cwd room) %)
                               (fx/session-edited-files room (:cwd room)))})))))

(defn- broad-add-refusal
  "Refuse hook: error text when a broad add would stage files another room
   edited, else nil."
  [ops {:keys [get-state room-id cwd]}]
  (when (and get-state (some broad-add? ops))
    (let [dir  (op-cwd cwd (first (filter broad-add? ops)))
          hits (foreign-sweep (dirty-files dir) (other-room-edits (get-state) room-id))]
      (when (seq hits)
        (str "git lock: refusing a broad add/commit (-A, ., -u, commit -a) — it "
             "would stage files another room is working on:\n"
             (str/join "\n" (map (fn [{:keys [label files]}]
                                   (str "  \"" label "\": "
                                        (str/join ", " (map #(node-path/relative dir %) files))))
                                 hits))
             "\nStage your own files explicitly (git add -- <paths>).")))))

(defn ops
  "The index-mutating git ops a tool call runs (parsed argvs), [] for none."
  [tool-name arguments]
  (->> (tool-call-argvs {:name tool-name :arguments arguments})
       (map parse-argv)
       (filterv locking?)))

(def hold
  "The git-index hold (registered in xi.holds/registry)."
  {:id         :git-index
   :label      "git index"
   :ops        (fn [tool-name arguments _cwd] (ops tool-name arguments))
   :key        (fn [op cwd] (toplevel (op-cwd cwd op)))
   :cwd-key    toplevel
   :lease-path lease-path
   :stale?     (fn [top l] (stale-clean? l {:now (js/Date.now) :staged (staged-files top)}))
   :settle?    (fn [top] (empty? (staged-files top)))
   :refuse     broad-add-refusal
   :detail     (fn [top] (staged-summary (staged-files top)))
   :on-acquire (fn [top fresh?]
                 (when fresh?
                   (when-let [s (staged-summary (staged-files top))]
                     (str "⚠ index already had staged files no room owns" s
                          " — they will be part of this room's next commit"))))
   :wait-ms    wait-ms
   :hint       "commit or unstage there, or run /release"})
