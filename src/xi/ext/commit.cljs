(ns xi.ext.commit
  "Git commit workflow extension — hunk-level staging and commit tools.

   `/commit` builds a prompt from a live git overview. Reading git is I/O,
   so the command stays pure and defers to a :commit/start effect that
   gathers the overview and submits the prompt.

   The commit tool needs user approval, but tool exec-fns can't raise
   dialogs (they only get {:cwd}). So approval is enforced by a tool-gate
   intercept that confirms via the gate ctx before the tool runs."
  (:require [clojure.string :as str]))

;; ── Git plumbing (impure edge) ────────────────────────────────────────────────

(defn- git-sync
  "Run a git command synchronously, return stdout string."
  [cwd & args]
  (let [proc (js/Bun.spawnSync
              (clj->js (cons "git" args))
              #js {:stdout "pipe" :stderr "pipe"
                   :cwd (or cwd (.cwd js/process))})]
    (str (.toString (.-stdout proc)))))

(defn- git-overview-sync
  "Overview of both staged and unstaged changes."
  [cwd]
  (let [staged    (git-sync cwd "diff" "--cached" "--stat")
        unstaged  (git-sync cwd "diff" "--stat")
        untracked (git-sync cwd "ls-files" "--others" "--exclude-standard")]
    (str (when (seq (str/trim staged))    (str "Staged:\n" staged))
         (when (seq (str/trim unstaged))  (str "Unstaged:\n" unstaged))
         (when (seq (str/trim untracked)) (str "Untracked:\n" untracked)))))

(defn- run-git
  "Run a git command, return promise of {:content [...] :is-error bool}."
  [args cwd]
  (js/Promise.
   (fn [resolve _reject]
     (let [proc (js/Bun.spawn
                 (clj->js (cons "git" args))
                 #js {:stdout "pipe" :stderr "pipe"
                      :cwd (or cwd (.cwd js/process))})]
       (-> (js/Promise.all #js [(.text (.-stdout proc))
                                (.text (.-stderr proc))
                                (.-exited proc)])
           (.then (fn [results]
                    (let [stdout (aget results 0)
                          stderr (aget results 1)
                          code   (aget results 2)
                          error? (and code (not= code 0))
                          output (str (when (seq stdout) stdout)
                                      (when (and (seq stderr) error?)
                                        (str "\nSTDERR: " stderr))
                                      (when error?
                                        (str "\nExit code: " code)))]
                      (resolve
                       {:content  [{:type "text"
                                    :text (if (seq output) output "Done.")}]
                        :is-error error?})))))))))

;; ── Prompt builder ──────────────────────────────────────────────────────────

(defn- build-commit-prompt [args overview]
  (str "Review the current git changes and create a commit.\n\n"
       "Current changes:\n```\n" overview "```\n\n"
       "Steps:\n"
       "1. Review the actual diffs with git_file_diff to understand the changes\n"
       "2. Stage the appropriate files with git_stage_hunks\n"
       "3. Write a clear conventional commit message and commit with git_commit_with_user_approval\n\n"
       "Use conventional commit format (feat:, fix:, refactor:, chore:, docs:, etc.).\n"
       "Keep the commit message concise and descriptive. Do NOT add co-authored-by or generated-with lines.\n"
       "When writing commit messages for fixes consider the chat session history and make semantic commit why this was changed and not outline what was changed\n"
       (when (seq args)
         (str "\nContext from the user: " args))))

;; ── Command + effect ──────────────────────────────────────────────────────────

(defn- commit-command
  "/commit [context] — pure: defer the git read to an effect."
  [_st {:keys [room-id args]}]
  {:effects [[:commit/start {:room-id room-id :args args}]]})

(defn- commit-start-fx
  "Gather a git overview for the room's cwd and submit the commit prompt."
  [{:keys [dispatch! get-state]} {:keys [room-id args]}]
  (let [cwd      (get-in (get-state) [:rooms room-id :cwd])
        overview (git-overview-sync cwd)]
    (dispatch! {:type :prompt/submit :room-id room-id
                :text (build-commit-prompt args overview)})))

;; ── Tools ──────────────────────────────────────────────────────────────────

(defn- git-overview [{:keys [staged]} {:keys [cwd]}]
  (-> (run-git (if staged ["diff" "--cached" "--stat"] ["diff" "--stat"]) cwd)
      (.then (fn [result]
               (if staged
                 result
                 (let [untracked (str/trim (git-sync cwd "ls-files" "--others" "--exclude-standard"))]
                   (if (seq untracked)
                     (update-in result [:content 0 :text]
                                #(str % "\nUntracked:\n" untracked))
                     result)))))))

(defn- git-file-diff [{:keys [files staged]} {:keys [cwd]}]
  (run-git (concat (if staged ["diff" "--cached"] ["diff"]) ["--"] files) cwd))

(defn- git-hunk [{:keys [file staged]} {:keys [cwd]}]
  (run-git (concat (if staged ["diff" "--cached"] ["diff"]) ["-U3" "--" file]) cwd))

(defn- git-stage-hunks [{:keys [files]} {:keys [cwd]}]
  (run-git (concat ["add" "--"] files) cwd))

(defn- git-commit [{:keys [message files]} {:keys [cwd]}]
  (-> (if (seq files)
        (run-git (concat ["add" "--"] files) cwd)
        (js/Promise.resolve {:content [{:type "text" :text ""}]}))
      (.then (fn [_] (run-git ["commit" "-m" message] cwd)))))

(def ^:private tool-defs
  [{:name "git_overview"
    :description "Show changed/staged files with stat summary and line counts."
    :input_schema {:type "object"
                   :properties {:staged {:type "boolean" :description "Show staged changes instead"}}
                   :required []}}
   {:name "git_file_diff"
    :description "Show diff for specific files."
    :input_schema {:type "object"
                   :properties {:files  {:type "array" :items {:type "string"} :description "File paths to diff"}
                                :staged {:type "boolean"}}
                   :required ["files"]}}
   {:name "git_hunk"
    :description "Show individual hunks from a file diff with 1-based indices."
    :input_schema {:type "object"
                   :properties {:file   {:type "string" :description "File path"}
                                :staged {:type "boolean"}}
                   :required ["file"]}}
   {:name "git_stage_hunks"
    :description "Stage specific files or all changes."
    :input_schema {:type "object"
                   :properties {:files {:type "array" :items {:type "string"} :description "Files to stage"}}
                   :required ["files"]}}
   {:name "git_commit_with_user_approval"
    :description "Create a git commit. The message should follow conventional commit format."
    :input_schema {:type "object"
                   :properties {:message {:type "string" :description "Commit message"}
                                :files   {:type "array" :items {:type "string"} :description "Files to stage before commit"}}
                   :required ["message"]}}])

;; ── Approval gate ───────────────────────────────────────────────────────────

(defn- tool-gate
  "Confirm before committing. Tools can't raise dialogs, so the approval
   lives here: confirm via the gate ctx, allow the tool-call on yes, or
   short-circuit with a cancelled result on no."
  [tool-call {:keys [confirm!]}]
  (let [{:keys [name arguments]} tool-call]
    (if (and (= name "git_commit_with_user_approval") confirm!)
      (-> (confirm! (str "Commit: " (first (str/split-lines (or (:message arguments) "")))))
          (.then (fn [approved?]
                   (if approved?
                     tool-call
                     {:intercepted true
                      :result {:content  [{:type "text" :text "Commit cancelled by user."}]
                               :is-error false}}))))
      tool-call)))

(def extension
  {:id               :commit
   :commands         [{:name "commit"
                       :description "Review changes and create a git commit"
                       :handler commit-command}]
   :fx               {:commit/start commit-start-fx}
   :tool-gate        tool-gate
   :tool-definitions tool-defs
   :tool-registry    {"git_overview"                   git-overview
                      "git_file_diff"                  git-file-diff
                      "git_hunk"                       git-hunk
                      "git_stage_hunks"                git-stage-hunks
                      "git_commit_with_user_approval"  git-commit}})
