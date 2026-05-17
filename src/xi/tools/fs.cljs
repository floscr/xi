(ns xi.tools.fs
  "Shared filesystem utilities for tools."
  (:require ["node:fs" :as fs]
            ["node:path" :as node-path]))

(defn resolve-path
  "Resolve a path relative to cwd. Falls back to process.cwd when cwd is nil."
  [path cwd]
  (.resolve node-path (or cwd (.cwd js/process)) path))

(defn file-exists?
  "Check if a resolved path exists on disk."
  [resolved]
  (fs/existsSync resolved))

(defn ensure-parent-dirs
  "Create parent directories for path if they don't exist."
  [resolved]
  (let [dir (.dirname node-path resolved)]
    (when-not (fs/existsSync dir)
      (fs/mkdirSync dir #js {:recursive true}))))

(defn assert-file-exists
  "Check that file exists relative to cwd. Returns a tool error result (promise)
   when the file is missing, nil when it exists."
  [file cwd]
  (let [resolved (resolve-path file cwd)]
    (when-not (fs/existsSync resolved)
      (js/Promise.resolve
       {:content [{:type "text" :text (str "File not found: " file)}]
        :is-error true}))))
