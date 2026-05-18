(ns xi.highlight.grammar.scss)

(def scss
  [   {:pattern "[{}()]" :token :punctuation}
   {:pattern "\\s+" :token :text}
   {:pattern "//.*?\\n" :token :comment}
   {:pattern "/\\*.*?\\*/" :token :comment}])
