(ns xi.highlight.grammar.zed)

(def zed
  [   {:pattern "\\n" :token :text}
   {:pattern "\\s+" :token :text}
   {:pattern "//.*?\\n" :token :comment}
   {:pattern "/(\\\\\\n)?[*][\\w\\W]*?[*](\\\\\\n)?/" :token :comment}
   {:pattern "/(\\\\\\n)?[*][\\w\\W]*" :token :comment}
   {:pattern "(definition)\\b" :token :keyword-type}
   {:pattern "(relation)\\b" :token :keyword}
   {:pattern "(permission)\\b" :token :keyword-decl}
   {:pattern "[a-zA-Z_]\\w*/" :token :name-var}
   {:pattern "[a-zA-Z_]\\w*" :token :text}
   {:pattern "#[a-zA-Z_]\\w*" :token :name-var}
   {:pattern "[+%=><|^!?/\\-*&~:]" :token :operator}
   {:pattern "[{}()\\[\\],.;]" :token :punctuation}])
