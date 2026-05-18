(ns xi.highlight.grammar.myghty)

(def myghty
  [   {:pattern "\\s+" :token :text}
   {:pattern "</&>" :token :keyword}
   {:pattern "(?<=^)#[^\\n]*(\\n|\\Z)" :token :comment}])
