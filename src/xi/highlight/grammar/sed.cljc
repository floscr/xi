(ns xi.highlight.grammar.sed)

(def sed
  [   {:pattern "\\s+" :token :text}
   {:pattern "#.*$" :token :comment}
   {:pattern "[0-9]+" :token :number}
   {:pattern "\\$" :token :operator}
   {:pattern "[{};,!]" :token :punctuation}
   {:pattern "[dDFgGhHlnNpPqQxz=]" :token :keyword}])
