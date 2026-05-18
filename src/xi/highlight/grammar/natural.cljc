(ns xi.highlight.grammar.natural)

(def natural
  [   {:pattern "(?:END-DEFINE|END-IF|END-FOR|END-SUBROUTINE|END-ERROR|END|IGNORE)\\b" :token :keyword}
   {:pattern "(?:INIT|CONST)\\s*<\\b" :token :keyword}
   {:pattern "(?<=(=|-)>)([\\w\\-~]+)(?=\\()" :token :name-fn}
   {:pattern "[0-9]+" :token :number}
   {:pattern "(?<=(\\s|.))(AND|OR|NOT|EQUAL|NE|EQ|GT|GE|LT|LE)\\b" :token :operator}
   {:pattern "[?*<>=\\-+&]" :token :operator}
   {:pattern "'(''|[^'])*'" :token :string}
   {:pattern "`([^`])*`" :token :string}
   {:pattern "[/;:()\\[\\],.]" :token :punctuation}
   {:pattern "\\s+" :token :text}
   {:pattern "^\\*.*$" :token :comment}
   {:pattern "/\\*.*$" :token :comment}
   {:pattern "[#+]?[\\w\\-\\d]+" :token :name-var}
   {:pattern "\\([a-zA-z]\\d*\\)" :token :text}])
