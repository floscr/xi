(ns xi.highlight.grammar.moonscript)

(def moonscript
  [   {:pattern "#!(.*?)$" :token :comment}])
