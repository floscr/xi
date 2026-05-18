(ns xi.highlight.grammar.ini)

(def ini
  [   {:pattern "\\s+" :token :text}
   {:pattern "[;#].*" :token :comment}
   {:pattern "\\[.*?\\]$" :token :keyword}
   {:pattern "(.+?)$" :token :name-var}])
