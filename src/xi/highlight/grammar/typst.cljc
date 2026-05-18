(ns xi.highlight.grammar.typst)

(def typst
  [   {:pattern "^\\s*=+.*$" :token :keyword}
   {:pattern "[*][^*]*[*]" :token :keyword}
   {:pattern "_[^_]*_" :token :text}
   {:pattern "`[^`]*`" :token :string}
   {:pattern "<[a-zA-Z_][a-zA-Z0-9_-]*>" :token :name-var}
   {:pattern "@[a-zA-Z_][a-zA-Z0-9_-]*" :token :name-var}
   {:pattern "\\\\#" :token :text}
   {:pattern "```(?:.|\\n)*?```" :token :string}
   {:pattern "https?://[0-9a-zA-Z~/%#&=\\&#x27;,;.+?]*" :token :text}
   {:pattern "(\\-\\-\\-|\\\\|\\~|\\-\\-|\\.\\.\\.)\\B" :token :punctuation}
   {:pattern "\\\\\\[" :token :punctuation}
   {:pattern "\\\\\\]" :token :punctuation}
   {:pattern "[ \\t]+\\n?|\\n" :token :text}
   {:pattern "((?![*_$`<@\\\\#\\] ]|https?://).)+" :token :text}
   {:pattern "//.*$" :token :comment}
   {:pattern "/[*](.|\\n)*?[*]/" :token :comment}
   {:pattern "(\\#true|\\#false|\\#none|\\#auto)\\b" :token :keyword}
   {:pattern "#[a-zA-Z_][a-zA-Z0-9_]*" :token :name-var}
   {:pattern "#0x[0-9a-fA-F]+" :token :number}
   {:pattern "#0b[01]+" :token :number}
   {:pattern "#0o[0-7]+" :token :number}
   {:pattern "#[0-9]+[\\.e][0-9]+" :token :number}
   {:pattern "#[0-9]+" :token :number}])
