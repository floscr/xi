(ns xi.highlight.grammar.mathematica)

(def mathematica
  [   {:pattern "([a-zA-Z]+[A-Za-z0-9]*`)" :token :name-var}
   {:pattern "([A-Za-z0-9]*_+[A-Za-z0-9]*)" :token :name-var}
   {:pattern "#\\d*" :token :name-var}
   {:pattern "([a-zA-Z]+[a-zA-Z0-9]*)" :token :text}
   {:pattern "-?\\d+\\.\\d*" :token :number}
   {:pattern "-?\\d*\\.\\d+" :token :number}
   {:pattern "-?\\d+" :token :number}
   {:pattern "(!===|@@@|===|/;|:=|->|:>|/\\.|=\\.|~~|<=|@@|/@|&&|\\|\\||//|<>|;;|>=|-|@|!|\\^|/|\\*|\\?|\\+|&|<|>|=|\\|)" :token :operator}
   {:pattern "(,|;|\\(|\\)|\\[|\\]|\\{|\\})" :token :punctuation}
   {:pattern "\".*?\"" :token :string}
   {:pattern "\\s+" :token :text}])
