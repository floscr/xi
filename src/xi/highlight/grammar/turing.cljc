(ns xi.highlight.grammar.turing)

(def turing
  [   {:pattern "\\n" :token :text}
   {:pattern "\\s+" :token :text}
   {:pattern "\\\\\\n" :token :text}
   {:pattern "%(.*?)\\n" :token :comment}
   {:pattern "/(\\\\\\n)?[*](.|\\n)*?[*](\\\\\\n)?/" :token :comment}
   {:pattern "(var|fcn|function|proc|procedure|process|class|end|record|type|begin|case|loop|for|const|union|monitor|module|handler)\\b" :token :keyword-decl}
   {:pattern "(all|asm|assert|bind|bits|body|break|by|cheat|checked|close|condition|decreasing|def|deferred|else|elsif|exit|export|external|flexible|fork|forward|free|get|if|implement|import|include|inherit|init|invariant|label|new|objectclass|of|opaque|open|packed|pause|pervasive|post|pre|priority|put|quit|read|register|result|seek|self|set|signal|skip|tag|tell|then|timeout|to|unchecked|unqualified|wait|when|write)\\b" :token :keyword}
   {:pattern "(true|false)\\b" :token :keyword}
   {:pattern "(addressint|boolean|pointer|string|array|real4|real8|nat1|int8|int4|int2|nat2|nat4|nat8|int1|real|char|enum|nat|int)\\b" :token :keyword-type}
   {:pattern "\\d+i" :token :number}
   {:pattern "\\d+\\.\\d*([Ee][-+]\\d+)?i" :token :number}
   {:pattern "\\.\\d+([Ee][-+]\\d+)?i" :token :number}
   {:pattern "\\d+[Ee][-+]\\d+i" :token :number}
   {:pattern "\\d+(\\.\\d+[eE][+\\-]?\\d+|\\.\\d*|[eE][+\\-]?\\d+)" :token :number}
   {:pattern "\\.\\d+([eE][+\\-]?\\d+)?" :token :number}
   {:pattern "0[0-7]+" :token :number}
   {:pattern "0[xX][0-9a-fA-F]+" :token :number}
   {:pattern "(0|[1-9][0-9]*)" :token :number}
   {:pattern "(div|mod|rem|\\*\\*|=|<|>|>=|<=|not=|not|and|or|xor|=>|in|shl|shr|->|~|~=|~in|&|:=|\\.\\.|[\\^+\\-*/&#])" :token :operator}
   {:pattern "'(\\\\['\"\\\\abfnrtv]|\\\\x[0-9a-fA-F]{2}|\\\\[0-7]{1,3}|\\\\u[0-9a-fA-F]{4}|\\\\U[0-9a-fA-F]{8}|[^\\\\])'" :token :string-char}
   {:pattern "\"(\\\\\\\\|\\\\\"|[^\"])*\"" :token :string}
   {:pattern "[()\\[\\]{}.,:]" :token :punctuation}
   {:pattern "[^\\W\\d]\\w*" :token :name-var}])
