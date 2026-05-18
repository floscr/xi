(ns xi.highlight.grammar.io)

(def io
  [   {:pattern "\\n" :token :text}
   {:pattern "\\s+" :token :text}
   {:pattern "//(.*?)\\n" :token :comment}
   {:pattern "#(.*?)\\n" :token :comment}
   {:pattern "/(\\\\\\n)?[*](.|\\n)*?[*](\\\\\\n)?/" :token :comment}
   {:pattern "\"(\\\\\\\\|\\\\\"|[^\"])*\"" :token :string}
   {:pattern "::=|:=|=|\\(|\\)|;|,|\\*|-|\\+|>|<|@|!|/|\\||\\^|\\.|%|&|\\[|\\]|\\{|\\}" :token :operator}
   {:pattern "(clone|do|doFile|doString|method|for|if|else|elseif|then)\\b" :token :keyword}
   {:pattern "(nil|false|true)\\b" :token :name-var}
   {:pattern "(Object|list|List|Map|args|Sequence|Coroutine|File)\\b" :token :name-builtin}
   {:pattern "[a-zA-Z_]\\w*" :token :text}
   {:pattern "(\\d+\\.?\\d*|\\d*\\.\\d+)([eE][+-]?[0-9]+)?" :token :number}
   {:pattern "\\d+" :token :number}])
