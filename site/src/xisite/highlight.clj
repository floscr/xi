(ns xisite.highlight
  "Build-time syntax highlighting with xi's own grammars
   (resources/highlight/grammars/*.edn, on the classpath via bb.edn :paths).
   A JVM port of xi's regex-walking tokenizer: rules are tried in order at
   each position, first match wins, unmatched characters become text."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import [java.util.regex Pattern]))

(def ^:private registry
  {"sh"         "bash"
   "shell"      "bash"
   "bash"       "bash"
   "zsh"        "bash"
   "clojure"    "clojure"
   "clj"        "clojure"
   "cljs"       "clojure"
   "cljc"       "clojure"
   "edn"        "clojure"
   "json"       "json"
   "yaml"       "yaml"
   "yml"        "yaml"
   "typescript" "typescript"
   "ts"         "typescript"
   "javascript" "javascript"
   "js"         "javascript"
   "python"     "python"
   "py"         "python"
   "css"        "css"
   "html"       "html"
   "diff"       "diff"
   "nix"        "nix"
   "toml"       "toml"
   "markdown"   "markdown"
   "md"         "markdown"})

(defn- compile-rules
  "Rules whose JS-flavoured regex doesn't compile on the JVM are skipped."
  [rules]
  (into []
        (keep (fn [{:keys [pattern token]}]
                (try
                  {:pattern (Pattern/compile pattern Pattern/MULTILINE)
                   :token token}
                  (catch Exception _ nil))))
        rules))

(defonce ^:private grammar-cache (atom {}))

(defn grammar
  "Compiled rules for `lang`, or nil when there is no grammar."
  [lang]
  (when-let [file (registry lang)]
    (or (@grammar-cache file)
        (when-let [res (io/resource (str "highlight/grammars/" file ".edn"))]
          (let [rules (compile-rules (edn/read-string (slurp res)))]
            (swap! grammar-cache assoc file rules)
            rules)))))

(defn tokenize [rules ^String source]
  (let [len (.length source)
        matchers (mapv (fn [{:keys [^Pattern pattern token]}]
                         {:matcher (doto (.matcher pattern source)
                                     (.useTransparentBounds true)
                                     (.useAnchoringBounds false))
                          :token token})
                       rules)]
    (loop [pos 0
           tokens (transient [])]
      (if (>= pos len)
        (persistent! tokens)
        (let [match (loop [ms matchers]
                      (when (seq ms)
                        (let [{:keys [^java.util.regex.Matcher matcher token]} (first ms)]
                          (if (and (try
                                     (.region matcher pos len)
                                     (.lookingAt matcher)
                                     (catch Exception _ false))
                                   (pos? (- (.end matcher) pos)))
                            {:type token :value (.group matcher)}
                            (recur (next ms))))))]
          (if match
            (recur (+ pos (count (:value match)))
                   (conj! tokens match))
            (recur (inc pos)
                   (conj! tokens {:type :text :value (str (.charAt source pos))}))))))))

(defn- merge-adjacent [tokens]
  (reduce
   (fn [acc tok]
     (let [prev (peek acc)]
       (if (and prev (= (:type prev) (:type tok)))
         (conj (pop acc) (update prev :value str (:value tok)))
         (conj acc tok))))
   []
   tokens))

(def ^:private class-map
  {:comment         "hl-comment"
   :string          "hl-string"
   :string-char     "hl-string"
   :string-symbol   "hl-symbol"
   :number          "hl-number"
   :keyword         "hl-keyword"
   :keyword-decl    "hl-keyword"
   :keyword-special "hl-keyword"
   :name-builtin    "hl-builtin"
   :name-fn         "hl-fn"
   :name-var        "hl-var"
   :operator        "hl-operator"
   :punctuation     "hl-punct"
   :reader          "hl-operator"})

(defn highlight
  "Hiccup children for a <code> element: strings for plain text,
   [:span.hl-*] for tokens. The raw string when the language is unknown."
  [lang source]
  (if-let [rules (some-> lang grammar)]
    (mapv (fn [{:keys [type value]}]
            (if-let [cls (class-map type)]
              [:span {:class cls} value]
              value))
          (merge-adjacent (tokenize rules source)))
    [source]))
