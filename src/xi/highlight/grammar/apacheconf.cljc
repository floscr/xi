(ns xi.highlight.grammar.apacheconf)

(def apacheconf
  [   {:pattern "\\s+" :token :text}
   {:pattern "(#.*?)$" :token :comment}
   {:pattern "\\.+" :token :text}])
