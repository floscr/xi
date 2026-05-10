(ns xi.tools.util
  "Shared utilities for tools."
  (:require [clojure.string :as str]
            ["node:child_process" :as cp]))

(defn git-root
  "Return the git repository root for `dir`, or nil if not in a git repo."
  [dir]
  (try
    (-> (cp/execSync "git rev-parse --show-toplevel"
                     #js {:cwd dir :encoding "utf8"})
        str/trim)
    (catch :default _ nil)))
