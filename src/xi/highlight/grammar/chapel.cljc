(ns xi.highlight.grammar.chapel)

(def chapel
  [   {:pattern "\\n" :token :text}
   {:pattern "\\s+" :token :text}
   {:pattern "\\\\\\n" :token :text}
   {:pattern "//(.*?)\\n" :token :comment}
   {:pattern "/(\\\\\\n)?[*](.|\\n)*?[*](\\\\\\n)?/" :token :comment}
   {:pattern "(config|const|inout|param|type|out|ref|var|in)\\b" :token :keyword-decl}
   {:pattern "(false|none|true|nil)\\b" :token :keyword}
   {:pattern "(complex|nothing|opaque|string|locale|bytes|range|imag|real|bool|uint|void|int)\\b" :token :keyword-type}
   {:pattern "(implements|forwarding|prototype|otherwise|subdomain|primitive|unmanaged|override|borrowed|lifetime|coforall|continue|private|require|dmapped|cobegin|foreach|lambda|sparse|shared|domain|pragma|reduce|except|export|extern|throws|forall|delete|return|noinit|single|import|select|public|inline|serial|atomic|defer|break|local|index|throw|catch|label|begin|where|while|align|yield|owned|only|this|sync|with|scan|else|enum|init|when|then|let|for|try|use|new|zip|if|by|as|on|do)\\b" :token :keyword}
   {:pattern "\\d+i" :token :number}
   {:pattern "\\d+\\.\\d*([Ee][-+]\\d+)?i" :token :number}
   {:pattern "\\.\\d+([Ee][-+]\\d+)?i" :token :number}
   {:pattern "\\d+[Ee][-+]\\d+i" :token :number}
   {:pattern "(\\d*\\.\\d+)([eE][+-]?[0-9]+)?i?" :token :number}
   {:pattern "\\d+[eE][+-]?[0-9]+i?" :token :number}
   {:pattern "0[bB][01]+" :token :number}
   {:pattern "0[xX][0-9a-fA-F]+" :token :number}
   {:pattern "0[oO][0-7]+" :token :number}
   {:pattern "[0-9]+" :token :number}
   {:pattern "\"(\\\\\\\\|\\\\\"|[^\"])*\"" :token :string}
   {:pattern "'(\\\\\\\\|\\\\'|[^'])*'" :token :string}
   {:pattern "(=|\\+=|-=|\\*=|/=|\\*\\*=|%=|&=|\\|=|\\^=|&&=|\\|\\|=|<<=|>>=|<=>|<~>|\\.\\.|by|#|\\.\\.\\.|&&|\\|\\||!|&|\\||\\^|~|<<|>>|==|!=|<=|>=|<|>|[+\\-*/%]|\\*\\*)" :token :operator}
   {:pattern "[:;,.?()\\[\\]{}]" :token :punctuation}
   {:pattern "[a-zA-Z_][\\w$]*" :token :name-var}])
