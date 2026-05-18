(ns xi.highlight.grammar.puppet)

(def puppet
  [   {:pattern "[]{}:(),;[]" :token :punctuation}
   {:pattern "[^\\S\\n]+" :token :text}
   {:pattern "\\s*#.*$" :token :comment}
   {:pattern "/(\\\\\\n)?[*](.|\\n)*?[*](\\\\\\n)?/" :token :comment}
   {:pattern "[a-zA-Z_]\\w*" :token :name-var}
   {:pattern "\\$\\S+" :token :name-var}
   {:pattern "(\\d+\\.\\d*|\\d*\\.\\d+)([eE][+-]?[0-9]+)?j?" :token :number}
   {:pattern "\\d+[eE][+-]?[0-9]+j?" :token :number}
   {:pattern "0[0-7]+j?" :token :number}
   {:pattern "0[xX][a-fA-F0-9]+" :token :number}
   {:pattern "\\d+L" :token :number}
   {:pattern "\\d+j?" :token :number}
   {:pattern "(=>|\\?|<|>|=|\\+|-|/|\\*|~|!|\\|)" :token :operator}
   {:pattern "(in|and|or|not)\\b" :token :operator}
   {:pattern "\"([^\"])*\"" :token :string}
   {:pattern "'(\\\\'|[^'])*'" :token :string}])
