(ns xi.highlight.grammar.bibtex)

(def bibtex
  [   {:pattern "@comment" :token :comment}
   {:pattern ".+" :token :comment}
   {:pattern "\\s+" :token :text}])
