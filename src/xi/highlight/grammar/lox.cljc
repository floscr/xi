(ns xi.highlight.grammar.lox)

(def lox
  [   {:pattern "//.*?\\n" :token :comment}
   {:pattern "(and|break|case|continue|default|else|for|if|or|print|return|super|switch|this|while)\\b" :token :keyword}
   {:pattern "(false|nil|true)\\b" :token :keyword}
   {:pattern "\\d+(\\.\\d*|[eE][+\\-]?\\d+)" :token :number}
   {:pattern "[0-9][0-9]*" :token :number}
   {:pattern "\"(\\\\\\\\|\\\\\"|[^\"])*\"" :token :string}
   {:pattern "!|\\+|-|\\*|/|<|>|=" :token :operator}
   {:pattern "[{}():;.,]" :token :punctuation}
   {:pattern "[a-zA-Z_]\\w*" :token :name-var}
   {:pattern "\\n" :token :text}
   {:pattern "\\s+" :token :text}])
