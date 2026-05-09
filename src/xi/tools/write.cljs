(ns xi.tools.write
  "Write file tool."
  (:require ["node:fs" :as fs]
            ["node:path" :as node-path]))

(defn execute
  "Write content to a file. Creates parent directories if needed."
  [{:keys [path content]} {:keys [cwd]}]
  (try
    (let [resolved (if cwd
                     (.resolve node-path cwd path)
                     (.resolve node-path path))
          dir (.dirname node-path resolved)]
      (when-not (fs/existsSync dir)
        (fs/mkdirSync dir #js {:recursive true}))
      (fs/writeFileSync resolved content "utf8")
      (let [bytes (.-length (js/Buffer.from content "utf8"))]
        {:content [{:type "text" :text (str "Successfully wrote " bytes " bytes to " path)}]}))
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
