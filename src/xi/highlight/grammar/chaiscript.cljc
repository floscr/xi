(ns xi.highlight.grammar.chaiscript)

(def chaiscript
  [   {:pattern "\\n" :token :text}
   {:pattern "[^\\S\\n]+" :token :text}
   {:pattern "[})\\].]" :token :punctuation}
   {:pattern "[=+\\-*/]" :token :operator}
   {:pattern "(attr|def|fun)\\b" :token :keyword}
   {:pattern "(true|false)\\b" :token :keyword}
   {:pattern "(eval|throw)\\b" :token :name-builtin}
   {:pattern "`\\S+`" :token :name-builtin}
   {:pattern "[$a-zA-Z_]\\w*" :token :name-var}
   {:pattern "[0-9][0-9]*\\.[0-9]+([eE][0-9]+)?[fd]?" :token :number}
   {:pattern "0x[0-9a-fA-F]+" :token :number}
   {:pattern "[0-9]+" :token :number}
   {:pattern "'(\\\\\\\\|\\\\'|[^'])*'" :token :string}
   {:pattern "\\s+" :token :text}
   {:pattern "//.*?\\n" :token :comment}
   {:pattern "/\\*.*?\\*/" :token :comment}
   {:pattern "^\\#.*?\\n" :token :comment}])
