(ns xi.highlight.grammar.java)

(def java
  [   {:pattern "[^\\S\\n]+" :token :text}
   {:pattern "/\\*.*?\\*/" :token :comment}
   {:pattern "@[^\\W\\d][\\w.]*" :token :name-builtin}
   {:pattern "(boolean|byte|char|double|float|int|long|short|void)\\b" :token :keyword-type}
   {:pattern "(true|false|null)\\b" :token :keyword}
   {:pattern "&#x27;\\\\.&#x27;|&#x27;[^\\\\]&#x27;|&#x27;\\\\u[0-9a-fA-F]{4}&#x27;" :token :string-char}
   {:pattern "([^\\W\\d]|\\$)[\\w$]*" :token :text}
   {:pattern "0[xX][0-9a-fA-F][0-9a-fA-F_]*[lL]?" :token :number}
   {:pattern "0[bB][01][01_]*[lL]?" :token :number}
   {:pattern "0[0-7_]+[lL]?" :token :number}
   {:pattern "0|[1-9][0-9_]*[lL]?" :token :number}
   {:pattern "[~^*!%&\\[\\]<>|+=/?-]" :token :operator}
   {:pattern "[{}();:.,]" :token :punctuation}
   {:pattern "\\n" :token :text}])
