(ns xi.ext.style-editor-js
  "Compile-time macro that inlines the style-editor browser JS into the
   ClojureScript bundle. Mirrors xi.ext.element-picker-js / xi.ext.chrome-mcp.defs:
   the runtime never reads the file, so there is no runtime path/classpath
   dependency — the script is baked in at compile time.

   resources/ is not on the classpath, so the file is read by repo-relative
   path at compile time."
  (:require [clojure.java.io :as io]))

(def ^:private editor-file "resources/style-editor/style-editor.js")

(defmacro inline-style-editor-js
  "Read the style-editor browser JS at compile time and return it as a string
   literal, or \"\" when the resource is absent."
  []
  (let [f (io/file editor-file)]
    (if (.exists f)
      (slurp f)
      "")))
