(ns xi.highlight.grammar.antlr)

(def antlr
  [   {:pattern "\\s+" :token :text}
   {:pattern "//.*$" :token :comment}
   {:pattern "/\\*(.|\\n)*?\\*/" :token :comment}])
