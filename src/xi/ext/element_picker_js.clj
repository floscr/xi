(ns xi.ext.element-picker-js
  "Compile-time macro that inlines the element-picker browser JS into the
   ClojureScript bundle. Mirrors xi.ext.chrome-mcp.defs / xi.highlight.bundle:
   the runtime never reads the file, so there is no runtime path/classpath
   dependency — the picker script is baked in at compile time.

   resources/ is not on the classpath, so the file is read by repo-relative
   path at compile time."
  (:require [clojure.java.io :as io]))

(def ^:private picker-file "resources/element-picker/picker.js")

(defmacro inline-picker-js
  "Read the element-picker browser JS at compile time and return it as a
   string literal, or \"\" when the resource is absent."
  []
  (let [f (io/file picker-file)]
    (if (.exists f)
      (slurp f)
      "")))
