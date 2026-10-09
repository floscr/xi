(ns xi.ext.diff.git
  "Git plumbing for the diff extension — the impure edge that shells out to
   git (optionally routed through difftastic) to produce diff text for the
   various /diff sources.

   Honors xi.ext.diff.difft/*diff-engine*: when :difft the diff-producing
   invocations render structural output via GIT_EXTERNAL_DIFF instead of
   git's native unified diff."
  (:require [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:path" :as node-path]
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

(defn- file-git-root
  "Absolute toplevel of the work tree containing `file` (an absolute path),
   or nil when the file lies outside any git repo."
  [file]
  (some-> (:ok (git-out (.dirname node-path file) ["rev-parse" "--show-toplevel"]))
          str/trim not-empty))

(defn session-diff-text-by-repo
  "Like session-diff-text, but for absolute `paths` that may span several
   repos (a parent repo, a sibling checkout): groups the files by the work
   tree containing each, diffs every group inside its own repo against
   (base-of root), and concatenates the results. Files outside any repo are
   synthesized via --no-index. Paths are realpath'd first so symlinked
   prefixes relativize correctly against git's physical toplevel."
  [paths base-of]
  (let [paths   (mapv (fn [p] (try (fs/realpathSync p) (catch :default _ p))) paths)
        root-of (into {} (map (juxt identity file-git-root)) (distinct paths))]
    (->> (distinct (map root-of paths))
         (map (fn [root]
                (let [files (filterv #(= root (root-of %)) paths)]
                  (if root
                    (session-diff-text root (base-of root)
                                       (mapv #(.relative node-path root %) files))
                    (str/join "\n" (mapv #(no-index-diff (.dirname node-path %) %) files))))))
         (remove str/blank?)
         (str/join "\n"))))

(defn session-commits-text
  "Unified diff of every commit made during the session (base..HEAD)."
  [cwd base]
  (when base
    (some-> (:ok (git-diff-out cwd ["diff" base "HEAD"])) str/trim not-empty)))

(def ^:private commit-line-format "--format=%H%x1f%h%x1f%s%x1f%cr%x1f%an")

(defn- commit-lines
  "Parse `git log` output in commit-line-format to [{:sha :short :subject
   :rel-time :author}]."
  [out]
  (some->> out
           str/split-lines
           (remove str/blank?)
           (mapv (fn [line]
                   (let [[sha short subject rel-time author] (str/split line #"\x1f")]
                     {:sha sha :short short :subject subject :rel-time rel-time
                      :author author})))))

(defn session-commits-list
  "Metadata for every commit made during the session (base..HEAD), newest
   first — official git_commit-tool commits and plain shell `git commit`s
   alike, since both are ordinary commits in the range. Each entry:
   {:sha :short :subject :rel-time :author}. Empty when there is no base or no
   commits."
  [cwd base]
  (when base
    (commit-lines (:ok (git-out cwd ["log" commit-line-format (str base "..HEAD")])))))

(defn log-list
  "The last `n` commits of HEAD's history, newest first, shaped like
   session-commits-list. nil outside a repo or before the first commit."
  [cwd n]
  (commit-lines (:ok (git-out cwd ["log" (str "--max-count=" n) commit-line-format]))))

(defn resolve-commit
  "Full sha for a ref (commit) in cwd, or nil when it either doesn't resolve or
   is no longer reachable from HEAD — the filter that drops dead commits
   (amended/rebased/reset away). The reachability test matters because an
   amended commit's object still lingers in the store, so existence alone
   wouldn't prune it; `merge-base --is-ancestor` (exit 0 ⇒ in HEAD's history)
   does."
  [cwd ref]
  (when-let [sha (some-> (:ok (git-out cwd ["rev-parse" "--verify" "--quiet"
                                            (str ref "^{commit}")]))
                         str/trim not-empty)]
    (when (:ok (git-out cwd ["merge-base" "--is-ancestor" sha "HEAD"]))
      sha)))

(defn session-commits-from-refs
  "Metadata for the commits made this session, resolved from the abbreviated
   refs collected off the room history (fx/session-commit-refs). Resolves each
   ref to a full sha (dropping dead ones), dedupes, and returns
   [{:sha :short :subject :rel-time}] newest first. Empty when none survive."
  [cwd refs]
  (let [shas (->> refs (keep #(resolve-commit cwd %)) distinct vec)]
    (when (seq shas)
      (commit-lines (:ok (git-out cwd (into ["log" "--no-walk=sorted" commit-line-format]
                                            shas)))))))

(defn commit-show-text
  "Unified diff for a single commit (git show). Honors *diff-engine*. The
   commit-message preamble git prepends is ignored by the diff parser (it only
   reads from the first `diff --git`), so this feeds the diff viewer directly."
  [cwd sha]
  (some-> (:ok (git-diff-out cwd ["show" sha])) str/trim not-empty))

(defn commit-info
  "Structured metadata for one commit — {:sha :short :author :date :rel-time
   :subject :body}. Feeds the diff viewer's commit header (message + info) since
   the raw `git show` preamble is discarded by the unified-diff parser. nil when
   the sha doesn't resolve. :body is nil when the commit has no body."
  [cwd sha]
  (some-> (:ok (git-out cwd ["show" "-s" "--date=format:%Y-%m-%d %H:%M"
                            "--format=%H%x1f%h%x1f%an%x1f%ad%x1f%cr%x1f%s%x1f%b"
                            sha]))
          str/trim not-empty
          (as-> s
              (let [[full short author date rel-time subject body] (str/split s #"\x1f")]
                {:sha full :short short :author author :date date
                 :rel-time rel-time :subject subject
                 :body (some-> body str/trim not-empty)}))))

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
