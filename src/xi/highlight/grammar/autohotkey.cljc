(ns xi.highlight.grammar.autohotkey)

(def autohotkey
  [   {:pattern "\\s+;.*?$" :token :comment}
   {:pattern "^;.*?$" :token :comment}
   {:pattern "[]{}(),;[]" :token :punctuation}
   {:pattern "(in|is|and|or|not)\\b" :token :operator}
   {:pattern "\\%[a-zA-Z_#@$][\\w#@$]*\\%" :token :name-var}
   {:pattern "!=|==|:=|\\.=|<<|>>|[-~+/*%=<>&^|?:!.]" :token :operator}
   {:pattern "&quot;" :token :string}
   {:pattern "[a-zA-Z_#@$][\\w#@$]*" :token :text}
   {:pattern "\\\\|\\&#x27;" :token :text}
   {:pattern "\\`([,%`abfnrtv\\-+;])" :token :string}
   {:pattern "(\\d+\\.\\d*|\\d*\\.\\d+)([eE][+-]?[0-9]+)?" :token :number}
   {:pattern "\\d+[eE][+-]?[0-9]+" :token :number}
   {:pattern "0\\d+" :token :number}
   {:pattern "0[xX][a-fA-F0-9]+" :token :number}
   {:pattern "\\d+L" :token :number}
   {:pattern "\\d+" :token :number}
   {:pattern "[^\\S\\n]" :token :text}])
