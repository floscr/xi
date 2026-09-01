(ns xi.ext.design-mode-js
  "Compile-time macro that inlines the design-mode browser JS into the
   ClojureScript bundle. Mirrors xi.ext.element-picker-js: the runtime never
   reads the file, so there is no runtime path/classpath dependency — the
   design script is baked in at compile time.

   resources/ is not on the classpath, so the file is read by repo-relative
   path at compile time."
  (:require [clojure.java.io :as io]))

(def ^:private design-file "resources/design-mode/design.js")

(defmacro inline-design-js
  "Read the design-mode browser JS at compile time and return it as a string
   literal, or \"\" when the resource is absent."
  []
  (let [f (io/file design-file)]
    (if (.exists f)
      (slurp f)
      "")))
