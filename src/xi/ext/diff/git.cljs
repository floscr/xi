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
   :difft the diff is rendered by difftastic via GIT_EXTERNAL_DIFF (forced
   with --ext-diff, which `git show` needs) instead of git's native unified
   diff. `prefix` (see path-prefix) is for difftastic's :json records, whose
   paths git's --src-prefix doesn't reach."
  ([cwd args] (git-diff-out cwd args nil))
  ([cwd args prefix]
   (if (= difft/*diff-engine* :difft)
     (try
       (let [[sub & more] args
             proc (js/Bun.spawnSync (into-array (list* "git" sub "--ext-diff" more))
                                    #js {:cwd cwd :env (difft/difft-env prefix)})]
         (if (zero? (.-exitCode proc))
           {:ok (.toString (.-stdout proc) "utf-8")}
           {:err (str/trim (.toString (.-stderr proc) "utf-8"))}))
       (catch :default e {:err (str e)}))
     (git-out cwd args))))

(defn- prefix-args
  "git diff flags that put `prefix` (see path-prefix) in front of every path
   in the headers, after the usual a/ and b/."
  [prefix]
  (when (seq prefix)
    [(str "--src-prefix=a/" prefix) (str "--dst-prefix=b/" prefix)]))

(defn- no-index-diff
  "Synthesize a diff for a single untracked file via diff --no-index. stdout
   is read regardless of exit code — --no-index exits 1 on diffs. Honors
   *diff-engine* so untracked files render with the chosen renderer too.
   \"\" when git can't run, e.g. in a directory that no longer exists."
  ([cwd f] (no-index-diff cwd f nil))
  ([cwd f prefix]
   (try
     (let [opts (cond-> #js {:cwd cwd}
                  (= difft/*diff-engine* :difft) (doto (aset "env" (difft/difft-env prefix))))
           p (js/Bun.spawnSync
              (into-array (concat ["git" "diff" "--no-index"] (prefix-args prefix)
                                  ["--" "/dev/null" f]))
              opts)]
       (.toString (.-stdout p) "utf-8"))
     (catch :default _ ""))))

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
   synthesized via --no-index so freshly created files still show. `prefix`
   (see path-prefix) is prepended to the paths in the headers."
  ([cwd base files] (session-diff-text cwd base files nil))
  ([cwd base files prefix]
   (let [{tracked false untracked true}
         (group-by #(some? (:err (git-out cwd ["ls-files" "--error-unmatch" "--" %])))
                   files)
         tracked-diff   (when (seq tracked)
                          (:ok (git-diff-out cwd (concat (if base ["diff" base] ["diff"])
                                                         (prefix-args prefix)
                                                         ["--"] tracked)
                                             prefix)))
         untracked-diff (when (seq untracked)
                          (str/join "\n" (map #(no-index-diff cwd % prefix) untracked)))]
     (->> [tracked-diff untracked-diff]
          (remove str/blank?)
          (str/join "\n")))))

(defn- existing-dir
  "The nearest directory of `file` that still exists — git can't run in a
   directory the session deleted or moved away."
  [file]
  (loop [dir (.dirname node-path file)]
    (if (or (fs/existsSync dir) (= dir (.dirname node-path dir)))
      dir
      (recur (.dirname node-path dir)))))

(defn- file-git-root
  "Absolute toplevel of the work tree containing `file` (an absolute path),
   or nil when the file lies outside any git repo."
  [file]
  (some-> (:ok (git-out (existing-dir file) ["rev-parse" "--show-toplevel"]))
          str/trim not-empty))

(defn- realpath [p]
  (try (fs/realpathSync p) (catch :default _ p)))

(defn- path-prefix
  "What to put before paths relative to `dir` so they resolve from `base`
   (xi.ext.file-view.core/locate-file): \"\" for base itself, `sub/` for a
   directory inside it, else the absolute `/dir/`."
  [base dir]
  (let [rel (.relative node-path base dir)]
    (cond
      (= "" rel)                   ""
      (str/starts-with? rel "..")  (str dir "/")
      :else                        (str rel "/"))))

(defn session-diff-text-by-repo
  "Like session-diff-text, but for absolute `paths` that may span several
   repos (a parent repo, a sibling checkout): groups the files by the work
   tree containing each, diffs every group inside its own repo against
   (base-of root), and concatenates the results. Files outside any repo are
   synthesized via --no-index. Paths outside the room's repo (`cwd`'s git
   root, else `cwd`) carry a path-prefix so the viewer can open them. Paths
   are realpath'd first so symlinked prefixes relativize correctly against
   git's physical toplevel."
  [cwd paths base-of]
  (let [paths   (mapv realpath paths)
        base    (or (some-> (:ok (git-out cwd ["rev-parse" "--show-toplevel"]))
                            str/trim not-empty)
                    (realpath cwd))
        root-of (into {} (map (juxt identity file-git-root)) (distinct paths))]
    (->> (distinct (map root-of paths))
         (map (fn [root]
                (let [files (filterv #(= root (root-of %)) paths)]
                  (if root
                    (session-diff-text root (base-of root)
                                       (mapv #(.relative node-path root %) files)
                                       (path-prefix base root))
                    (str/join "\n" (mapv (fn [f]
                                           (let [dir (.dirname node-path f)]
                                             (no-index-diff dir (.basename node-path f)
                                                            (path-prefix base dir))))
                                         files))))))
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
