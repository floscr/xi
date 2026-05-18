(ns xi.highlight.grammar.yaml)

(def yaml
  [   {:pattern "^---" :token :name-var}
   {:pattern "^\\.\\.\\." :token :name-var}
   {:pattern "[\\n?]?\\s*- " :token :text}
   {:pattern "#.*$" :token :comment}
   {:pattern "!![^\\s]+" :token :comment}
   {:pattern "&[^\\s]+" :token :comment}
   {:pattern "\\*[^\\s]+" :token :comment}
   {:pattern "^%include\\s+[^\\n\\r]+" :token :comment}
   {:pattern "[?:,\\[\\]]" :token :punctuation}
   {:pattern "." :token :text}
   {:pattern "\\s+" :token :text}
   {:pattern "\\n+" :token :text}
   {:pattern "\"[^\"\\n].*\": " :token :keyword}
   {:pattern "(false|False|FALSE|true|True|TRUE|null|Off|off|yes|Yes|YES|OFF|On|ON|no|No|on|NO|n|N|Y|y)\\b" :token :keyword}
   {:pattern "\"(?:\\\\.|[^\"])*\"" :token :string}
   {:pattern "'(?:\\\\.|[^'])*'" :token :string}
   {:pattern "\\d\\d\\d\\d-\\d\\d-\\d\\d([T ]\\d\\d:\\d\\d:\\d\\d(\\.\\d+)?(Z|\\s+[-+]\\d+)?)?" :token :string}
   {:pattern "\\b[+\\-]?(0x[\\da-f]+|0o[0-7]+|(\\d+\\.?\\d*|\\.?\\d+)(e[\\+\\-]?\\d+)?|\\.inf|\\.nan)\\b" :token :number}
   {:pattern "[^\\{\\}\\[\\]\\?,\\:\\!\\-\\*&\\@].*" :token :string}])
