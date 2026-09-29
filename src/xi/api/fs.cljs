(ns xi.api.fs
  "Rules-gated file access for user extensions. Every call is a decision
   request — `{:tool :read|:write|:ls :path … :extension id}` — so the same
   rules as the agent's file tools apply (credential paths denied, writes
   outside the repo ask, your own rules, …), plus the extension defaults:
   its own data dir (`data-dir`) is free, credential paths are off-limits.

   All functions take the ctx the extension was handed first and return
   Promises; a refusal rejects with the rule's message. Relative paths
   resolve against the room cwd, or the data dir outside a room. I/O runs on
   the symlink-canonical path the rules checked."
  (:refer-clojure :exclude [read list exists?])
  (:require [xi.api.core :as core]
            [xi.paths :as paths]
            ["node:fs" :as fs]
            ["node:path" :as node-path]))

(defn- resolved [req]
  (paths/real-resolve (:effective-cwd req) (str (:path req))))

(defn data-dir
  "The extension's own data directory (not created until the first write)."
  [{:keys [extension]}]
  (paths/extension-data-dir extension))

(defn read
  "→ Promise<string> — the file's UTF-8 contents."
  [ctx path]
  (-> (core/gate! ctx {:tool :read :path (str path)})
      (.then #(.readFile (.-promises fs) (resolved %) "utf8"))))

(defn write
  "Write UTF-8 `content`, creating parent dirs. → Promise<path written>."
  [ctx path content]
  (let [content (str content)]
    (-> (core/gate! ctx {:tool      :write
                         :path      (str path)
                         ;; lets an :ask dialog preview the change as a diff
                         :arguments {:path (str path) :content content}})
        (.then (fn [req]
                 (let [target (resolved req)]
                   (-> (.mkdir (.-promises fs) (node-path/dirname target) #js {:recursive true})
                       (.then #(.writeFile (.-promises fs) target content "utf8"))
                       (.then (constantly target)))))))))

(defn list
  "→ Promise<[name …]> of a directory's entries; subdirectories end in \"/\"."
  [ctx path]
  (-> (core/gate! ctx {:tool :ls :path (str path)})
      (.then #(.readdir (.-promises fs) (resolved %) #js {:withFileTypes true}))
      (.then (fn [entries]
               (vec (sort (map (fn [^js e]
                                 (str (.-name e) (when (.isDirectory e) "/")))
                               entries)))))))

(defn exists?
  "→ Promise<bool>."
  [ctx path]
  (-> (core/gate! ctx {:tool :ls :path (str path)})
      (.then #(fs/existsSync (resolved %)))))
