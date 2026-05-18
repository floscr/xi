(ns xi.highlight.grammar.mason)

(def mason
  [   {:pattern "\\s+" :token :text}
   {:pattern "</&>" :token :keyword}
   {:pattern "(?<=^)#[^\\n]*(\\n|\\Z)" :token :comment}])
