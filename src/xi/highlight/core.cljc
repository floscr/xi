(ns xi.highlight.core
  "Minimal regex-walking syntax highlighter.
   Takes a grammar (ordered list of {:pattern :token} rules) and source text,
   returns a seq of {:type token-type :value matched-text} tokens."
  (:require [clojure.string :as str]))

(defn tokenize
  "Tokenize `source` using `grammar` (a vector of {:pattern regex-str :token keyword} rules).
   Returns a vector of {:type keyword :value string} maps.
   Rules are tried in order at each position; first match wins.
   Unmatched characters are emitted as :text tokens."
  [grammar source]
  (let [len (count source)
        ;; Pre-compile regexes with sticky flag for positional matching
        compiled (mapv (fn [{:keys [pattern token]}]
                         {:re (js/RegExp. pattern "y")
                          :token token})
                       grammar)]
    (loop [pos 0
           tokens (transient [])]
      (if (>= pos len)
        (persistent! tokens)
        (let [match (loop [rules compiled]
                      (when (seq rules)
                        (let [{:keys [re token]} (first rules)]
                          (set! (.-lastIndex re) pos)
                          (if-let [m (.exec re source)]
                            {:type token :value (aget m 0)}
                            (recur (next rules))))))]
          (if match
            (recur (+ pos (count (:value match)))
                   (conj! tokens match))
            ;; No rule matched — consume one char as plain text
            (recur (inc pos)
                   (conj! tokens {:type :text :value (.charAt source pos)}))))))))

(defn merge-adjacent
  "Merge consecutive tokens of the same type into single tokens.
   Reduces token count for rendering."
  [tokens]
  (when (seq tokens)
    (reduce
     (fn [acc tok]
       (let [prev (peek acc)]
         (if (and prev (= (:type prev) (:type tok)))
           (conj (pop acc) (update prev :value str (:value tok)))
           (conj acc tok))))
     []
     tokens)))
