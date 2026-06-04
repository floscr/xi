(ns xi.highlight.bundle
  "Bundled grammars for environments without filesystem access (browser).
   Grammars are inlined at compile time via macros."
  #?(:cljs (:require-macros [xi.highlight.bundle :refer [inline-registry inline-grammars]])))

;; Common languages bundled at compile time (~35KB of grammar data)
(def ^:private bundled-filenames
  ["bash" "c" "clojure" "cplusplus" "css" "diff" "docker" "elixir"
   "go" "hcl" "html" "java" "javascript" "json" "kotlin" "lua"
   "makefile" "mysql" "nix" "python" "react" "ruby" "rust" "swift"
   "terraform" "toml" "typescript" "xml" "yaml"])

#?(:cljs
   (def ^:private registry (inline-registry)))

#?(:cljs
   (def ^:private grammars (inline-grammars
                            ["bash" "c" "clojure" "cplusplus" "css" "diff" "docker" "elixir"
                             "go" "hcl" "html" "java" "javascript" "json" "kotlin" "lua"
                             "makefile" "mysql" "nix" "python" "react" "ruby" "rust" "swift"
                             "terraform" "toml" "typescript" "xml" "yaml"])))

(defn get-grammar
  "Look up a grammar by language name (case-insensitive).
   Returns the grammar vector or nil if unknown/not bundled."
  [lang]
  #?(:cljs
     (when lang
       (let [key (-> lang .toLowerCase .trim)]
         (when-let [filename (get registry key)]
           (get grammars filename))))
     :clj nil))
