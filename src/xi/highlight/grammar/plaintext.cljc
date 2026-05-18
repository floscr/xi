(ns xi.highlight.grammar.plaintext)

(def plaintext
  [   {:pattern ".+" :token :text}
   {:pattern "\\n" :token :text}])
