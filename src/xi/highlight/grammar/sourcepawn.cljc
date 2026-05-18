(ns xi.highlight.grammar.sourcepawn)

(def sourcepawn
  [   {:pattern "\\n" :token :text}
   {:pattern "\\s+" :token :text}
   {:pattern "\\\\\\n" :token :text}
   {:pattern "/(\\\\\\n)?/(\\n|(.|\\n)*?[^\\\\]\\n)" :token :comment}
   {:pattern "/(\\\\\\n)?\\*(.|\\n)*?\\*(\\\\\\n)?/" :token :comment}
   {:pattern "[{}]" :token :punctuation}
   {:pattern "L?&#x27;(\\\\.|\\\\[0-7]{1,3}|\\\\x[a-fA-F0-9]{1,2}|[^\\\\\\&#x27;\\n])&#x27;" :token :string-char}
   {:pattern "(\\d+\\.\\d*|\\.\\d+|\\d+)[eE][+-]?\\d+[LlUu]*" :token :number}
   {:pattern "(\\d+\\.\\d*|\\.\\d+|\\d+[fF])[fF]?" :token :number}
   {:pattern "0x[0-9a-fA-F]+[LlUu]*" :token :number}
   {:pattern "0[0-7]+[LlUu]*" :token :number}
   {:pattern "\\d+[LlUu]*" :token :number}
   {:pattern "[~!%^&*+=|?:<>/-]" :token :operator}
   {:pattern "[()\\[\\],.;]" :token :punctuation}
   {:pattern "(case|const|continue|native|default|else|enum|for|if|new|operator|public|return|sizeof|static|decl|struct|switch)\\b" :token :keyword}
   {:pattern "(bool|float|void|int|char)\\b" :token :keyword-type}
   {:pattern "(true|false)\\b" :token :keyword}
   {:pattern "[a-zA-Z_]\\w*" :token :text}])
