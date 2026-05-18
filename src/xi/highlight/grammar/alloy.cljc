(ns xi.highlight.grammar.alloy)

(def alloy
  [   {:pattern "--.*?$" :token :comment}
   {:pattern "//.*?$" :token :comment}
   {:pattern "/\\*.*?\\*/" :token :comment}
   {:pattern "[^\\S\\n]+" :token :text}
   {:pattern "(iden|univ|none)\\b" :token :keyword}
   {:pattern "(int|Int)\\b" :token :keyword-type}
   {:pattern "(var|this|abstract|extends|set|seq|one|lone|let)\\b" :token :keyword}
   {:pattern "(all|some|no|sum|disj|when|else)\\b" :token :keyword}
   {:pattern "(run|check|for|but|exactly|expect|as|steps)\\b" :token :keyword}
   {:pattern "(always|after|eventually|until|release)\\b" :token :keyword}
   {:pattern "(historically|before|once|since|triggered)\\b" :token :keyword}
   {:pattern "(and|or|implies|iff|in)\\b" :token :operator}
   {:pattern "!|#|&&|\\+\\+|<<|>>|>=|<=>|<=|\\.\\.|\\.|->" :token :operator}
   {:pattern "[-+/*%=<>&!^|~{}\\[\\]().\\&#x27;;]" :token :operator}
   {:pattern "[a-zA-Z_][\\w]*&quot;*" :token :text}
   {:pattern "[:,]" :token :punctuation}
   {:pattern "[0-9]+" :token :number}
   {:pattern "&quot;\\b(\\\\\\\\|\\\\[^\\\\]|[^&quot;\\\\])*&quot;" :token :string}
   {:pattern "\\n" :token :text}])
