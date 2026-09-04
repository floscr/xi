(ns xi.tools.read
  "Read file tool."
  (:require [xi.tools.fs :as tfs]
            [xi.tools.util :as util]
            ["node:fs" :as fs]))

(defn execute
  "Read a file's contents. Supports offset/limit for large files."
  [{:keys [path offset limit]} {:keys [cwd]}]
  (try
    (let [resolved (tfs/resolve-path path cwd)
          content (fs/readFileSync resolved "utf8")
          lines (.split content "\n")
          total (.-length lines)
          start (or offset 0)
          end (if limit (min (+ start limit) total) total)
          sliced (.slice lines start end)
          result (.join sliced "\n")
          truncated? (< end total)]
      {:content [{:type "text"
                  :text (str (when (pos? start)
                               (str "[showing lines " (inc start) "-" end " of " total "]\n"))
                             result
                             (when truncated?
                               (str "\n[" (- total end) " more lines, use offset=" end " to continue]"))
                             "\n[file-hash: " (util/content-hash content) "]")}]})
    (catch :default e
      {:content [{:type "text" :text (str "Error reading file: " (.-message e))}]
       :is-error true})))

(def definition
  {:name "read"
   :description "Read the contents of a file. Use offset/limit for large files."
   :input_schema {:type "object"
                  :properties {:path {:type "string" :description "Path to file"}
                               :offset {:type "integer" :description "Line offset (0-based)"}
                               :limit {:type "integer" :description "Max lines to return"}}
                  :required ["path"]}})
