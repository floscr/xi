(ns xi.highlight.grammar.spade)

(def spade
  [   {:pattern "#![^[\\r\\n].*$" :token :comment}])
