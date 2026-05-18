(ns xi.highlight.grammar.bnf)

(def bnf
  [   {:pattern "::=" :token :operator}
   {:pattern "[^<>:]+" :token :text}
   {:pattern "." :token :text}])
