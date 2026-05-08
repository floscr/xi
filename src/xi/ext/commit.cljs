(ns xi.ext.commit
  "Git commit workflow extension — hunk-level staging and commit tools."
  (:require [clojure.string :as str]))

(defn- run-git
  "Run a git command, return promise of {:content [...] :is-error bool}."
  [args]
  (js/Promise.
   (fn [resolve _reject]
     (let [proc (js/Bun.spawn
                 (clj->js (cons "git" args))
                 #js {:stdout "pipe" :stderr "pipe"
                      :cwd (.cwd js/process)})]
       (-> (js/Promise.all #js [(.text (.-stdout proc))
                                 (.text (.-stderr proc))])
           (.then (fn [results]
                    (let [stdout (aget results 0)
                          stderr (aget results 1)
                          code (.-exitCode proc)]
                      (resolve
                       {:content [{:type "text"
                                   :text (str (when (seq stdout) stdout)
                                              (when (and (seq stderr) (not= 0 code))
                                                (str "\nSTDERR: " stderr))
                                              (when (not= 0 code)
                                                (str "\nExit code: " code)))}]
                        :is-error (not= 0 code)})))))))))

(def extension
  {:name "commit"
   :tools [{:name "git_overview"
            :description "Show changed/staged files with stat summary and line counts."
            :input_schema {:type "object"
                           :properties {:staged {:type "boolean" :description "Show staged changes instead"}}
                           :required []}
            :execute (fn [{:keys [staged]}]
                       (run-git (if staged
                                  ["diff" "--cached" "--stat"]
                                  ["diff" "--stat"])))}

           {:name "git_file_diff"
            :description "Show diff for specific files."
            :input_schema {:type "object"
                           :properties {:files {:type "array" :items {:type "string"} :description "File paths to diff"}
                                        :staged {:type "boolean"}}
                           :required ["files"]}
            :execute (fn [{:keys [files staged]}]
                       (run-git (concat (if staged ["diff" "--cached"] ["diff"])
                                        ["--"] files)))}

           {:name "git_hunk"
            :description "Show individual hunks from a file diff with 1-based indices."
            :input_schema {:type "object"
                           :properties {:file {:type "string" :description "File path"}
                                        :staged {:type "boolean"}}
                           :required ["file"]}
            :execute (fn [{:keys [file staged]}]
                       (run-git (concat (if staged ["diff" "--cached"] ["diff"])
                                        ["-U3" "--" file])))}

           {:name "git_stage_hunks"
            :description "Stage specific files or all changes."
            :input_schema {:type "object"
                           :properties {:files {:type "array" :items {:type "string"} :description "Files to stage"}}
                           :required ["files"]}
            :execute (fn [{:keys [files]}]
                       (run-git (concat ["add" "--"] files)))}

           {:name "git_commit_with_user_approval"
            :description "Create a git commit. The message should follow conventional commit format."
            :input_schema {:type "object"
                           :properties {:message {:type "string" :description "Commit message"}
                                        :files {:type "array" :items {:type "string"} :description "Files to stage before commit"}}
                           :required ["message"]}
            :execute (fn [{:keys [message files]}]
                       (-> (if (seq files)
                             (run-git (concat ["add" "--"] files))
                             (js/Promise.resolve {:content [{:type "text" :text ""}]}))
                           (.then (fn [_]
                                    (run-git ["commit" "-m" message])))))}]})
