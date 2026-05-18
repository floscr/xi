(ns xi.highlight.grammar.armasm)

(def armasm
  [   {:pattern "svc\\s+\\w+" :token :name-var}
   {:pattern "\\s+" :token :text}
   {:pattern "[@;].*?\\n" :token :comment}
   {:pattern "/\\*.*?\\*/" :token :comment}])
