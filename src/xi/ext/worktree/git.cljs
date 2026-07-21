(ns xi.ext.worktree.git
  "Git worktree plumbing — the impure edge of the /worktree extension.

   Every fn shells out synchronously via Bun.spawnSync and returns plain
   data ({:ok :err :code} or parsed values), so the extension core stays a
   thin pure/effect layer on top."
  (:require [clojure.string :as str]))

(defn git
  "Run a git command synchronously in `cwd`. Returns
   {:ok <trimmed stdout> :err <trimmed stderr> :code <exit int>}."
  [cwd args]
  (let [proc (js/Bun.spawnSync
              (into-array (cons "git" args))
              #js {:stdout "pipe" :stderr "pipe"
                   :cwd (or cwd (.cwd js/process))})]
    {:ok   (str/trim (str (.toString (.-stdout proc))))
     :err  (str/trim (str (.toString (.-stderr proc))))
     :code (.-exitCode proc)}))

(defn ok?
  "Was the command successful (exit 0)?"
  [{:keys [code]}]
  (zero? code))

(defn repo-root
  "Absolute path of the working tree containing `cwd`, or nil if not a repo."
  [cwd]
  (let [{:keys [ok] :as r} (git cwd ["rev-parse" "--show-toplevel"])]
    (when (ok? r) (not-empty ok))))

(defn current-branch
  "Current branch name for `cwd` (nil in detached HEAD or on failure)."
  [cwd]
  (let [{:keys [ok] :as r} (git cwd ["rev-parse" "--abbrev-ref" "HEAD"])]
    (when (and (ok? r) (not= ok "HEAD")) (not-empty ok))))

(defn main-worktree-root
  "Root of the *main* working tree (the first entry of `git worktree list`),
   as seen from `cwd`. Works from inside a linked worktree."
  [cwd]
  (let [{:keys [ok] :as r} (git cwd ["worktree" "list" "--porcelain"])]
    (when (ok? r)
      (some (fn [line]
              (when (str/starts-with? line "worktree ")
                (subs line (count "worktree "))))
            (str/split-lines ok)))))

(defn worktrees
  "Parse `git worktree list --porcelain` into
   [{:path :branch :head :bare?} …] (main tree first)."
  [cwd]
  (let [{:keys [ok] :as r} (git cwd ["worktree" "list" "--porcelain"])]
    (when (ok? r)
      (->> (str/split ok #"\n\n")
           (remove str/blank?)
           (mapv (fn [block]
                   (reduce (fn [m line]
                             (cond
                               (str/starts-with? line "worktree ")
                               (assoc m :path (subs line 9))
                               (str/starts-with? line "HEAD ")
                               (assoc m :head (subs line 5))
                               (str/starts-with? line "branch ")
                               (assoc m :branch (str/replace (subs line 7)
                                                             #"^refs/heads/" ""))
                               (= line "bare")
                               (assoc m :bare? true)
                               :else m))
                           {}
                           (str/split-lines block))))))))

(defn clean?
  "True when `cwd`'s working tree has no changes (staged, unstaged, untracked)."
  [cwd]
  (let [{:keys [ok] :as r} (git cwd ["status" "--porcelain"])]
    (and (ok? r) (str/blank? ok))))

(defn branch-exists?
  "Does a local branch named `branch` already exist?"
  [cwd branch]
  (ok? (git cwd ["show-ref" "--verify" "--quiet" (str "refs/heads/" branch)])))

(defn add-worktree
  "Create a worktree at `path` on a new branch `branch` off `base`, run from
   `repo-root`. Returns the {:ok :err :code} of the git invocation."
  [repo-root path branch base]
  (git repo-root ["worktree" "add" "-b" branch path base]))

(defn abort-rebase
  "Abort an in-progress rebase in `cwd` (best effort)."
  [cwd]
  (git cwd ["rebase" "--abort"]))

(defn merge-branch
  "Integrate `branch` into the main tree with a *linear* history: rebase the
   worktree branch (checked out in `cwd`) onto `main-branch`, then
   fast-forward `main-root` to it. No merge commit is ever created.

   If the rebase can't apply cleanly it is aborted (leaving the worktree
   untouched) and the failing {:ok :err :code} is returned."
  [main-root cwd branch main-branch]
  (let [rb (git cwd ["rebase" main-branch])]
    (if (ok? rb)
      (git main-root ["merge" "--ff-only" branch])
      (do (abort-rebase cwd)
          rb))))

(defn remove-worktree
  "Remove the worktree at `path` (run from `repo-root`). `force?` drops the
   clean-tree safety check. Returns {:ok :err :code}."
  [repo-root path force?]
  (git repo-root (cond-> ["worktree" "remove" path] force? (conj "--force"))))

(defn delete-branch
  "Delete local `branch` from `repo-root`. `force?` uses -D (unmerged)."
  [repo-root branch force?]
  (git repo-root ["branch" (if force? "-D" "-d") branch]))
