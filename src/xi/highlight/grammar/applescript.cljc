(ns xi.highlight.grammar.applescript)

(def applescript
  [   {:pattern "\\s+" :token :text}
   {:pattern "¬\\n" :token :string}
   {:pattern "'s\\s+" :token :text}
   {:pattern "(--|#).*?$" :token :comment}
   {:pattern "[(){}!,.:]" :token :punctuation}
   {:pattern "(-|\\*|\\+|&|≠|>=?|<=?|=|≥|≤|/|÷|\\^)" :token :operator}
   {:pattern "\\b(global|local|prop(erty)?|set|get)\\b" :token :keyword}
   {:pattern "\\b(but|put|returning|the)\\b" :token :name-builtin}
   {:pattern "\\b(attachment|attribute run|character|day|month|paragraph|word|year)s?\\b" :token :name-builtin}
   {:pattern "\"(\\\\\\\\|\\\\\"|[^\"])*\"" :token :string}
   {:pattern "\\b([a-zA-Z]\\w*)\\b" :token :name-var}
   {:pattern "[-+]?(\\d+\\.\\d*|\\d*\\.\\d+)(E[-+][0-9]+)?" :token :number}
   {:pattern "[-+]?\\d+" :token :number}])
