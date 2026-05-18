(ns xi.highlight.grammar.reg)

(def reg
  [   {:pattern "Windows Registry Editor.*" :token :text}
   {:pattern "\\s+" :token :text}
   {:pattern "[;#].*" :token :comment}])
