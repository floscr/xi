(ns xi.ext.diff.git
  "Git plumbing for the diff extension — the impure edge that shells out to
   git (optionally routed through difftastic) to produce diff text for the
   various /diff sources.

   Honors xi.ext.diff.difft/*diff-engine*: when :difft the diff-producing
   invocations render structural output via GIT_EXTERNAL_DIFF instead of
   git's native unified diff."
  (:require [clojure.string :as str]
            [xi.ext.diff.difft :as difft]))

(defn- git-out
  "Run git synchronously in cwd. Returns {:ok stdout} or {:err message}."
  [cwd args]
  (try
    (let [proc (js/Bun.spawnSync (into-array (cons "git" args)) #js {:cwd cwd})]
      (if (zero? (.-exitCode proc))
        {:ok (.toString (.-stdout proc) "utf-8")}
        {:err (str/trim (.toString (.-stderr proc) "utf-8"))}))
    (catch :default e {:err (str e)})))

(defn git-diff-out
  "Like git-out, but for diff-producing invocations: when *diff-engine* is
   :difft the diff is rendered by difftastic (ANSI structural output) via
   GIT_EXTERNAL_DIFF instead of git's native unified diff."
  [cwd args]
  (if (= difft/*diff-engine* :difft)
    (try
      (let [proc (js/Bun.spawnSync (into-array (cons "git" args))
                                   #js {:cwd cwd :env (difft/difft-env)})]
        (if (zero? (.-exitCode proc))
          {:ok (.toString (.-stdout proc) "utf-8")}
          {:err (str/trim (.toString (.-stderr proc) "utf-8"))}))
      (catch :default e {:err (str e)}))
    (git-out cwd args)))

(defn- no-index-diff
  "Synthesize a diff for a single untracked file via diff --no-index. stdout
   is read regardless of exit code — --no-index exits 1 on diffs. Honors
   *diff-engine* so untracked files render with the chosen renderer too."
  [cwd f]
  (let [opts (cond-> #js {:cwd cwd}
               (= difft/*diff-engine* :difft) (doto (aset "env" (difft/difft-env))))
        p (js/Bun.spawnSync
           #js ["git" "diff" "--no-index" "--" "/dev/null" f]
           opts)]
    (.toString (.-stdout p) "utf-8")))

(defn- untracked-diff
  "Synthesize a unified diff for all untracked files in cwd."
  [cwd]
  (when-let [files (some->> (:ok (git-out cwd ["ls-files" "--others" "--exclude-standard"]))
                            str/trim str/split-lines (remove empty?) seq)]
    (str/join "\n" (map (partial no-index-diff cwd) files))))

(defn all-git-changes-text
  "Combined unified diff of every working-tree change in cwd — unstaged,
   staged, and untracked files. Honors *diff-engine*. Public so the roomless
   web git-status view can read it without a room."
  [cwd]
  (->> [(:ok (git-diff-out cwd ["diff"]))
        (:ok (git-diff-out cwd ["diff" "--staged"]))
        (untracked-diff cwd)]
       (remove str/blank?)
       (str/join "\n")
       str/trim))

(defn session-base-commit
  "The commit that was HEAD when the session started (works for resumed
   sessions too — derived from the session's :created timestamp)."
  [cwd created]
  (when created
    (some-> (:ok (git-out cwd ["rev-list" "-1" (str "--before=" created) "HEAD"]))
            str/trim not-empty)))

(defn session-diff-text
  "Unified diff of the session-edited files vs the session base commit.
   Tracked files diff against base; untracked (newly written) files are
   synthesized via --no-index so freshly created files still show."
  [cwd base files]
  (let [{tracked false untracked true}
        (group-by #(some? (:err (git-out cwd ["ls-files" "--error-unmatch" "--" %])))
                  files)
        tracked-diff   (when (seq tracked)
                         (:ok (git-diff-out cwd (concat (if base ["diff" base] ["diff"])
                                                        ["--"] tracked))))
        untracked-diff (when (seq untracked)
                         (str/join "\n" (map (partial no-index-diff cwd) untracked)))]
    (->> [tracked-diff untracked-diff]
         (remove str/blank?)
         (str/join "\n"))))

(defn session-commits-text
  "Unified diff of every commit made during the session (base..HEAD)."
  [cwd base]
  (when base
    (some-> (:ok (git-diff-out cwd ["diff" base "HEAD"])) str/trim not-empty)))

(defn git-ref?
  "True when ref resolves to a commit in cwd."
  [cwd ref]
  (boolean (:ok (git-out cwd ["rev-parse" "--verify" "--quiet" (str ref "^{commit}")]))))

(defn upstream-default-ref
  "Auto-detect the upstream branch to diff against: origin's default branch.
   Prefers origin/HEAD when set (e.g. set by clone), else the first of
   origin/main / origin/master that exists. nil when none is found."
  [cwd]
  (or (some-> (:ok (git-out cwd ["symbolic-ref" "--short" "refs/remotes/origin/HEAD"]))
              str/trim not-empty)
      (some (fn [ref] (when (git-ref? cwd ref) ref))
            ["origin/main" "origin/master"])))

(defn diff-against-ref
  "Unified diff of the working tree against the merge-base with ref — the
   changes this branch introduced since it diverged from ref (committed +
   uncommitted), PR-style. Falls back to a plain ref diff when there is no
   common ancestor. Returns {:ok ...} | {:err ...}."
  [cwd ref]
  (let [base (some-> (:ok (git-out cwd ["merge-base" ref "HEAD"])) str/trim not-empty)]
    (git-diff-out cwd ["diff" (or base ref)])))
