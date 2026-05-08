(ns xi.tools.ls
  "List directory contents tool."
  (:require [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:path" :as node-path]))

(defn execute
  "List directory contents with file types."
  [{:keys [path]}]
  (try
    (let [dir (or path ".")
          resolved (.resolve node-path dir)
          entries (fs/readdirSync resolved #js {:withFileTypes true})
          lines (mapv (fn [entry]
                        (let [name (.-name entry)]
                          (str (cond
                                 (.isDirectory entry) "d "
                                 ^boolean (.isSymbolicLink ^js entry) "l "
                                 :else "f ")
                               name)))
                      (sort-by #(.-name %) entries))]
      {:content [{:type "text" :text (str/join "\n" lines)}]})
    (catch :default e
      {:content [{:type "text" :text (str "Error listing directory: " (.-message e))}]
       :is-error true})))

(def definition
  {:name "ls"
   :description "List directory contents. Shows file type (d=dir, f=file, l=symlink) and name."
   :input_schema {:type "object"
                  :properties {:path {:type "string" :description "Directory path (default: current dir)"}}
                  :required []}})
