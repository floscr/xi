(ns xi.highlight.grammar.protocol-buffer)

(def protocol-buffer
  [   {:pattern "\\s+" :token :text}
   {:pattern "[,;{}\\[\\]()<>]" :token :punctuation}
   {:pattern "/(\\\\\\n)?/(\\n|(.|\\n)*?[^\\\\]\\n)" :token :comment}
   {:pattern "/(\\\\\\n)?\\*(.|\\n)*?\\*(\\\\\\n)?/" :token :comment}
   {:pattern "\\b(ctype|default|edition|export|local|max|option|optional|packed|public|repeated|required|reserved|returns|stream|syntax|to|weak)\\b" :token :keyword}
   {:pattern "\\b(extensions|map)\\b" :token :keyword-decl}
   {:pattern "(sfixed32|sfixed64|fixed32|fixed64|sint32|sint64|double|string|uint32|uint64|int32|float|int64|bytes|bool)\\b" :token :keyword-type}
   {:pattern "(true|false)\\b" :token :keyword}
   {:pattern "import\\b" :token :keyword}
   {:pattern "\\\".*?\\\"" :token :string}
   {:pattern "\\'.*?\\'" :token :string}
   {:pattern "(\\d+\\.\\d*|\\.\\d+|\\d+)[eE][+-]?\\d+[LlUu]*" :token :number}
   {:pattern "(\\d+\\.\\d*|\\.\\d+|\\d+[fF])[fF]?" :token :number}
   {:pattern "(\\-?(inf|nan))\\b" :token :number}
   {:pattern "0x[0-9a-fA-F]+[LlUu]*" :token :number}
   {:pattern "0[0-7]+[LlUu]*" :token :number}
   {:pattern "\\d+[LlUu]*" :token :number}
   {:pattern "[+-=]" :token :operator}
   {:pattern "[a-zA-Z_][\\w.]*" :token :text}])
