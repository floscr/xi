(ns xi.highlight.grammar.rego)

(def rego
  [   {:pattern "(package|import|as|not|with|default|else|some|in|if|contains)\\b" :token :keyword-decl}
   {:pattern "#[^\\r\\n]*" :token :comment}
   {:pattern "(true|false|null)\\b" :token :keyword}
   {:pattern "\\d+i" :token :number}
   {:pattern "\\d+\\.\\d*([Ee][-+]\\d+)?i" :token :number}
   {:pattern "\\.\\d+([Ee][-+]\\d+)?i" :token :number}
   {:pattern "\\d+[Ee][-+]\\d+i" :token :number}
   {:pattern "\\d+(\\.\\d+[eE][+\\-]?\\d+|\\.\\d*|[eE][+\\-]?\\d+)" :token :number}
   {:pattern "\\.\\d+([eE][+\\-]?\\d+)?" :token :number}
   {:pattern "(0|[1-9][0-9]*)" :token :number}
   {:pattern "\"\"\".*?\"\"\"" :token :string}
   {:pattern "\"(\\\\\\\\|\\\\\"|[^\"])*\"" :token :string}
   {:pattern "\\$/((?!/\\$).)*/\\$" :token :string}
   {:pattern "/(\\\\\\\\|\\\\\"|[^/])*/" :token :string}
   {:pattern "^(\\w+)" :token :text}
   {:pattern "[a-z_-][\\w-]*(?=\\()" :token :name-fn}
   {:pattern "[\\r\\n\\s]+" :token :text}
   {:pattern "[=<>!+-/*&|]" :token :operator}
   {:pattern ":=" :token :operator}
   {:pattern "[[\\]{}():;]+" :token :punctuation}
   {:pattern "[$a-zA-Z_]\\w*" :token :name-var}])
