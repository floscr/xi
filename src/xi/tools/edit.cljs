(ns xi.tools.edit
  "Edit file tool — exact text replacement."
  (:require [clojure.string :as str]
            [xi.tools.fs :as tfs]
            [xi.tools.util :as util]
            ["node:fs" :as fs]))

(defn apply-edit
  "Apply a single oldText→newText replacement. Returns {:ok content} or {:error msg}."
  [content {:keys [oldText newText]}]
  (let [idx (.indexOf content oldText)]
    (cond
      (= -1 idx)
      {:error (str "Could not find the exact text to replace. "
                   "First 60 chars of oldText: " (subs oldText 0 (min 60 (count oldText))))}

      ;; Check for multiple occurrences
      (not= -1 (.indexOf content oldText (inc idx)))
      {:error (str "Found multiple occurrences of the text. "
                   "Please provide more context to make it unique.")}

      :else
      {:ok (str (subs content 0 idx) newText (subs content (+ idx (count oldText))))})))

(defn execute
  "Edit a file using exact text replacement.
   Accepts a vec of {:oldText :newText} edits applied against the original file."
  [{:keys [path edits]} {:keys [cwd]}]
  (try
    (let [resolved (tfs/resolve-path path cwd)
          created? (when-not (tfs/file-exists? resolved)
                     (tfs/ensure-parent-dirs resolved)
                     (fs/writeFileSync resolved "" "utf8")
                     true)
          original (fs/readFileSync resolved "utf8")]
        (loop [content original
               [edit & remaining] edits
               applied 0]
          (if-not edit
            (do (fs/writeFileSync resolved content "utf8")
                (let [display-path (util/display-path resolved path cwd)
                      diff (util/unified-diff original content)
                      info (when created? (str "(created new file)\n"))]
                  {:content [{:type "text"
                              :text (str display-path "\n" info diff)}]}))
            (let [result (apply-edit content edit)]
              (if (:error result)
                {:content [{:type "text" :text (:error result)}]
                 :is-error true}
                (recur (:ok result) remaining (inc applied)))))))
    (catch :default e
      {:content [{:type "text" :text (str "Error editing file: " (.-message e))}]
       :is-error true})))

(def definition
  {:name "edit"
   :description "Edit a file using exact text replacement. Each edit specifies oldText (must match exactly) and newText. Edits are applied against the original file, not incrementally."
   :input_schema {:type "object"
                  :properties {:path {:type "string" :description "Path to file"}
                               :edits {:type "array"
                                       :description "One or more replacements"
                                       :items {:type "object"
                                               :properties {:oldText {:type "string" :description "Exact text to find"}
                                                            :newText {:type "string" :description "Text to replace with"}}
                                               :required ["oldText" "newText"]}}}
                  :required ["path" "edits"]}})
