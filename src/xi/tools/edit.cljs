(ns xi.tools.edit
  "Edit file tool — exact text replacement."
  (:require [clojure.string :as str]
            [xi.tools.fs :as tfs]
            [xi.tools.util :as util]
            ["node:fs" :as fs]))

(defn apply-edit
  "Apply a single oldText→newText replacement. Returns {:ok content} or {:error msg}."
  [content {:keys [oldText newText]}]
  (if (empty? oldText)
    ;; Empty oldText = prepend newText to content
    {:ok (str newText content)}
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
        {:ok (str (subs content 0 idx) newText (subs content (+ idx (count oldText))))}))))

(defn apply-edits
  "Apply `edits` in order to `content`. Returns {:ok content} or the first
   {:error msg}."
  [content edits]
  (reduce (fn [acc edit]
            (let [r (apply-edit (:ok acc) edit)]
              (if (:error r) (reduced r) r)))
          {:ok content}
          edits))

(defn- stale-message [path expected actual]
  (str "File has changed since it was last read "
       "(expected hash " expected ", current " actual "). Re-read "
       path " before editing."))

(defn validate
  "Dry-run `execute`'s checks without writing anything → the error message the
   call would fail with, or nil when it would apply. Lets the permission layer
   reject a doomed edit before asking the user to approve it."
  [{:keys [path edits expectedHash]} {:keys [cwd]}]
  (try
    (let [resolved (tfs/resolve-path path cwd)
          original (if (tfs/file-exists? resolved)
                     (fs/readFileSync resolved "utf8")
                     "")
          actual   (util/content-hash original)]
      (if (and expectedHash (not= expectedHash actual))
        (stale-message path expectedHash actual)
        (:error (apply-edits original edits))))
    (catch :default _ nil)))

(defn preview
  "Unified diff the edit would produce, without writing anything — shown in
   the permission dialog before the call runs. nil when the edits don't apply
   (see `validate`) or change nothing."
  [{:keys [path edits]} {:keys [cwd]}]
  (try
    (let [resolved (tfs/resolve-path path cwd)
          original (if (tfs/file-exists? resolved)
                     (fs/readFileSync resolved "utf8")
                     "")
          {:keys [ok]} (apply-edits original edits)]
      (when (and ok (not= ok original))
        (util/unified-diff original ok)))
    (catch :default _ nil)))

(defn execute
  "Edit a file using exact text replacement.
   Accepts a vec of {:oldText :newText} edits applied against the original file."
  [{:keys [path edits expectedHash]} {:keys [cwd]}]
  (try
    (let [resolved (tfs/resolve-path path cwd)
          created? (when-not (tfs/file-exists? resolved)
                     (tfs/ensure-parent-dirs resolved)
                     (fs/writeFileSync resolved "" "utf8")
                     true)
          original (fs/readFileSync resolved "utf8")]
      (if (and expectedHash (not= expectedHash (util/content-hash original)))
        {:content [{:type "text"
                    :text (stale-message path expectedHash (util/content-hash original))}]
         :is-error true}
        (let [result (apply-edits original edits)]
          (if (:error result)
            {:content [{:type "text" :text (:error result)}]
             :is-error true}
            (let [content (:ok result)]
              (fs/writeFileSync resolved content "utf8")
              (let [display-path (util/display-path resolved path cwd)
                    diff (util/unified-diff original content)
                    info (when created? (str "(created new file)\n"))]
                {:content [{:type "text"
                            :text (str display-path "\n" info diff
                                       "\n[file-hash: " (util/content-hash content) "]")}]}))))))
    (catch :default e
      {:content [{:type "text" :text (str "Error editing file: " (.-message e))}]
       :is-error true})))

(def definition
  {:name "edit"
   :description "Edit a file using exact text replacement. Each edit specifies oldText (must match exactly) and newText. Edits are applied against the original file, not incrementally. Optionally pass expectedHash (the [file-hash: ...] token from your last read of this file) to reject the edit if the file changed since — this prevents clobbering concurrent edits."
   :input_schema {:type "object"
                  :properties {:path {:type "string" :description "Path to file"}
                               :expectedHash {:type "string" :description "Optional freshness token from the last read ([file-hash: ...]). If set and the file changed since, the edit is rejected so you re-read first."}
                               :edits {:type "array"
                                       :description "One or more replacements"
                                       :items {:type "object"
                                               :properties {:oldText {:type "string" :description "Exact text to find"}
                                                            :newText {:type "string" :description "Text to replace with"}}
                                               :required ["oldText" "newText"]}}}
                  :required ["path" "edits"]}})
