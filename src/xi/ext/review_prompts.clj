(ns xi.ext.review-prompts
  "Compile-time macro that reads project-type review-prompt markdown files
   (resources/review/*.md) and embeds their content into the ClojureScript
   bundle. Keeps the built-in review checklists as editable .md files while
   shipping them as inline string constants (no runtime file dependency)."
  (:require [clojure.java.io :as io]))

(def ^:private review-dir "resources/review")

(defmacro inline-md
  "Read resources/review/<name>.md at compile time and return its content
   as a string. `name` is the base filename without extension (e.g.
   \"clojure\")."
  [name]
  (let [f (io/file review-dir (str name ".md"))]
    (when (.exists f)
      (slurp f))))
