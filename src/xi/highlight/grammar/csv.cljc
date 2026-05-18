(ns xi.highlight.grammar.csv)

(def csv
  [   {:pattern "\\r?\\n" :token :punctuation}
   {:pattern "," :token :punctuation}
   {:pattern "[^\\r\\n,]+" :token :string}])
