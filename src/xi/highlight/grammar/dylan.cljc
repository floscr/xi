(ns xi.highlight.grammar.dylan)

(def dylan
  [   {:pattern "\\s+" :token :text}
   {:pattern "//.*?\\n" :token :comment}])
