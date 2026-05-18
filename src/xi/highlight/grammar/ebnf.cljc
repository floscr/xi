(ns xi.highlight.grammar.ebnf)

(def ebnf
  [   {:pattern "\\s+" :token :text}
   {:pattern "([a-zA-Z][\\w \\-]*)" :token :keyword}])
