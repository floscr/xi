(ns xi.highlight.grammar.tablegen)

(def tablegen
  [   {:pattern "c?\"[^\"]*?\"" :token :string}
   {:pattern "\\$[_a-zA-Z][_\\w]*" :token :name-var}
   {:pattern "\\d*[_a-zA-Z][_\\w]*" :token :name-var}
   {:pattern "\\[\\{[\\w\\W]*?\\}\\]" :token :string}
   {:pattern "[+-]?\\d+|0x[\\da-fA-F]+|0b[01]+" :token :number}
   {:pattern "[=<>{}\\[\\]()*.,!:;]" :token :punctuation}
   {:pattern "^\\s*#(ifdef|ifndef)\\s+[_\\w][_\\w\\d]*" :token :comment}
   {:pattern "^\\s*#define\\s+[_\\w][_\\w\\d]*" :token :comment}
   {:pattern "^\\s*#endif" :token :comment}
   {:pattern "(\\n|\\s)+" :token :text}
   {:pattern "//.*?\\n" :token :comment}
   {:pattern "(multiclass|foreach|string|class|field|defm|bits|code|list|def|int|let|dag|bit|in)\\b" :token :keyword}])
