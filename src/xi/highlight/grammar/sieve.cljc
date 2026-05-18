(ns xi.highlight.grammar.sieve)

(def sieve
  [   {:pattern "\\s+" :token :text}
   {:pattern "[();,{}\\[\\]]" :token :punctuation}
   {:pattern "#.*$" :token :comment}
   {:pattern "/\\*.*\\*/" :token :comment}
   {:pattern "\"[^\"]*?\"" :token :string}])
