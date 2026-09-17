(ns xi.highlight.bundle
  "Compile-time macro that reads grammar EDN files and embeds them
   into the ClojureScript bundle. Used by the web client where
   Node.js fs APIs are not available.

   NOTE: the grammar EDN under resources/highlight/grammars/ is read at
   *macro-expansion time*, so editing a grammar file does NOT retrigger a
   web-build recompile on its own — shadow only watches source namespaces.
   Touch this namespace (or run a clean web build) to pick up grammar edits."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]))

(def ^:private grammars-dir "resources/highlight/grammars")

(defmacro inline-registry
  "Read registry.edn at compile time and return it as a map."
  []
  (let [f (io/file grammars-dir "registry.edn")]
    (when (.exists f)
      (edn/read-string (slurp f)))))

(defmacro inline-grammar
  "Read a single grammar EDN file at compile time.
   `filename` is the base name without extension (e.g. \"javascript\")."
  [filename]
  (let [f (io/file grammars-dir (str filename ".edn"))]
    (when (.exists f)
      (edn/read-string (slurp f)))))

(defmacro inline-grammars
  "Read a set of grammar files at compile time. Returns {filename grammar-vec}."
  [filenames]
  (into {}
        (for [fname filenames
              :let [f (io/file grammars-dir (str fname ".edn"))]
              :when (.exists f)]
          [fname (edn/read-string (slurp f))])))
