(ns xi.ext.chrome-mcp.defs
  "Compile-time macro that inlines the chrome-devtools-mcp tool definitions
   into the ClojureScript bundle. Mirrors xi.highlight.bundle: the runtime
   never reads the file, so there is no runtime path/classpath dependency —
   the defs are baked in at compile time.

   The resource is generated from chrome-devtools-mcp's live tools/list by
   scripts/sync-chrome-tools.mjs (`bb chrome:sync-tools`)."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]))

(def ^:private tools-file "resources/chrome/tools.edn")

(defmacro inline-tool-defs
  "Read the generated chrome tool defs at compile time and return them as a
   literal vector, or [] when the resource is absent."
  []
  (let [f (io/file tools-file)]
    (if (.exists f)
      (vec (edn/read-string (slurp f)))
      [])))
