(ns xi.highlight.grammar.json)

(def json
  [   {:pattern "\\s+" :token :text}
   {:pattern "(true|false|null)\\b" :token :keyword}
   {:pattern "-?(0|[1-9]\\d*)(\\.\\d+[eE](\\+|-)?\\d+|[eE](\\+|-)?\\d+|\\.\\d+)" :token :number}
   {:pattern "-?(0|[1-9]\\d*)" :token :number}
   {:pattern "\"(\\\\\\\\|\\\\\"|[^\"])*\"" :token :string}
   {:pattern "//.*?\\n" :token :comment}])
