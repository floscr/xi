(ns xi.ext.parmezan
  "Post-edit hook for Clojure files — runs parmezan CLI to fix unbalanced delimiters."
  (:require [clojure.string :as str]))

(defn- clojure-file? [path]
  (some #(str/ends-with? (str path) %)
        [".clj" ".cljs" ".cljc" ".edn" ".bb"]))

(defn- on-tool-execution-end [{:keys [tool-name arguments]}]
  (when (and (contains? #{"write" "edit"} tool-name)
             (clojure-file? (:path arguments)))
    (try
      (js/Bun.spawn #js ["parmezan" (:path arguments)]
                    #js {:stdout "ignore" :stderr "ignore"})
      (catch :default _e nil))))

(def extension
  {:name "parmezan"
   :hooks {:tool-execution-end on-tool-execution-end}})
