(ns xi.highlight.grammar.blitzbasic)

(def blitzbasic
  [   {:pattern "[ \\t]+" :token :text}
   {:pattern ";.*?\\n" :token :comment}
   {:pattern "[0-9]+\\.[0-9]*(?!\\.)" :token :number}
   {:pattern "\\.[0-9]+(?!\\.)" :token :number}
   {:pattern "[0-9]+" :token :number}
   {:pattern "\\$[0-9a-f]+" :token :number}
   {:pattern "\\%[10]+" :token :number}
   {:pattern "\\b(Before|Handle|After|First|Float|Last|Sgn|Abs|Not|And|Int|Mod|Str|Sar|Shr|Shl|Or)\\b" :token :operator}
   {:pattern "([+\\-*/~=<>^])" :token :operator}
   {:pattern "[(),:\\[\\]\\\\]" :token :punctuation}
   {:pattern "\\.([ \\t]*)([a-z]\\w*)" :token :name-var}
   {:pattern "\\b(Pi|True|False|Null)\\b" :token :keyword}
   {:pattern "\\b(Local|Global|Const|Field|Dim)\\b" :token :keyword-decl}
   {:pattern "\\b(Function|Restore|Default|Forever|Include|Return|Repeat|ElseIf|Delete|Insert|Select|EndIf|Until|While|Gosub|Type|Goto|Else|Data|Next|Step|Each|Case|Wend|Exit|Read|Then|For|New|Asc|Len|Chr|End|To|If)\\b" :token :keyword}])
