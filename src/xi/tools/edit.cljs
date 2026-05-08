(ns xi.tools.edit
  "Edit file tool — exact text replacement."
  (:require [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:path" :as node-path]))

(defn- apply-edit
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
  [{:keys [path edits]}]
  (try
    (let [resolved (.resolve node-path path)
          original (fs/readFileSync resolved "utf8")]
      (loop [content original
             [edit & remaining] edits
             applied 0]
        (if-not edit
          (do (fs/writeFileSync resolved content "utf8")
              {:content [{:type "text"
                          :text (str "Successfully applied " applied " edit(s) to " path)}]})
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
