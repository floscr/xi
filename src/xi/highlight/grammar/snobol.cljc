(ns xi.highlight.grammar.snobol)

(def snobol
  [   {:pattern "\\*.*\\n" :token :comment}
   {:pattern "-.*\\n" :token :comment}])
