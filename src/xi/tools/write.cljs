(ns xi.tools.write
  "Write file tool."
  (:require [clojure.string :as str]
            [xi.tools.fs :as tfs]
            [xi.tools.util :as util]
            ["node:fs" :as fs]))

(defn execute
  "Write content to a file. Creates parent directories if needed."
  [{:keys [path content]} {:keys [cwd]}]
  (try
    (let [resolved (tfs/resolve-path path cwd)]
      (tfs/ensure-parent-dirs resolved)
      (fs/writeFileSync resolved content "utf8")
      (let [display-path (util/display-path resolved path cwd)
            lines (str/split-lines content)
            max-preview 4
            preview-lines (take max-preview lines)
            preview (str/join "\n" preview-lines)
            remaining (- (count lines) max-preview)]
        {:content [{:type "text"
                    :text (str display-path "\n"
                               preview
                               (when (pos? remaining)
                                 (str "\n... (" remaining " more lines)")))}]}))
    (catch :default e
      {:content [{:type "text" :text (str "Error writing file: " (.-message e))}]
       :is-error true})))

(def definition
  {:name "write"
   :description "Write content to a file. Creates the file if it doesn't exist, overwrites if it does. Automatically creates parent directories."
   :input_schema {:type "object"
                  :properties {:path {:type "string" :description "Path to file"}
                               :content {:type "string" :description "Content to write"}}
                  :required ["path" "content"]}})
