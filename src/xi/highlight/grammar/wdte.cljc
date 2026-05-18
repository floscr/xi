(ns xi.highlight.grammar.wdte)

(def wdte
  [   {:pattern "\\n" :token :text}
   {:pattern "\\s+" :token :text}
   {:pattern "\\\\\\n" :token :text}
   {:pattern "#(.*?)\\n" :token :comment}
   {:pattern "-?[0-9]+" :token :number}
   {:pattern "-?[0-9]*\\.[0-9]+" :token :number}
   {:pattern "\"[^\"]*\"" :token :string}
   {:pattern "'[^']*'" :token :string}
   {:pattern "(default|switch|memo)\\b" :token :keyword}
   {:pattern "{|}|;|->|=>|\\(|\\)|\\[|\\]|\\." :token :operator}
   {:pattern "[^{};()[\\].\\s]+" :token :name-var}])
