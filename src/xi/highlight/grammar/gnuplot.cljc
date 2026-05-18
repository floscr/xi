(ns xi.highlight.grammar.gnuplot)

(def gnuplot
  [   {:pattern "else\\b" :token :keyword}
   {:pattern "@[a-zA-Z_]\\w*" :token :name-var}
   {:pattern ";" :token :keyword}
   {:pattern "[ \\t\\v\\f]+" :token :text}])
