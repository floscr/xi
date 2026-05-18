(ns xi.highlight.grammar.gleam)

(def gleam
  [   {:pattern "\\s+" :token :text}
   {:pattern "///(.*?)\\n" :token :string}
   {:pattern "//(.*?)\\n" :token :comment}
   {:pattern "(as|assert|case|opaque|panic|pub|todo)\\b" :token :keyword}
   {:pattern "(import|use)\\b" :token :keyword}
   {:pattern "(auto|const|delegate|derive|echo|else|if|implement|macro|test)\\b" :token :keyword}
   {:pattern "(let)\\b" :token :keyword-decl}
   {:pattern "(fn)\\b" :token :keyword}
   {:pattern "(type)\\b" :token :keyword}
   {:pattern "(True|False)\\b" :token :keyword}
   {:pattern "0[bB][01](_?[01])*" :token :number}
   {:pattern "0[oO][0-7](_?[0-7])*" :token :number}
   {:pattern "0[xX][\\da-fA-F](_?[\\dA-Fa-f])*" :token :number}
   {:pattern "\\d(_?\\d)*\\.\\d(_?\\d)*([eE][-+]?\\d(_?\\d)*)?" :token :number}
   {:pattern "\\d(_?\\d)*" :token :number}
   {:pattern "@([a-z_]\\w*[!?]?)" :token :name-var}
   {:pattern "[{}()\\[\\],]|[#(]|\\.\\.|<>|<<|>>" :token :punctuation}
   {:pattern ":|->" :token :operator}
   {:pattern "[+\\-*/%!=<>&|.]|<-" :token :operator}
   {:pattern "[A-Z][A-Za-z0-9_]*" :token :name-class}
   {:pattern "([a-z_]\\w*[!?]?)" :token :text}])
