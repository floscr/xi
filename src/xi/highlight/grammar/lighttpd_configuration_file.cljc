(ns xi.highlight.grammar.lighttpd-configuration-file)

(def lighttpd-configuration-file
  [   {:pattern "#.*\\n" :token :comment}
   {:pattern "/\\S*" :token :text}
   {:pattern "[a-zA-Z._-]+" :token :keyword}
   {:pattern "\\d+\\.\\d+\\.\\d+\\.\\d+(?:/\\d+)?" :token :number}
   {:pattern "[0-9]+" :token :number}
   {:pattern "=>|=~|\\+=|==|=|\\+" :token :operator}
   {:pattern "\\$[A-Z]+" :token :name-builtin}
   {:pattern "[(){}\\[\\],]" :token :punctuation}
   {:pattern "\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"" :token :string}
   {:pattern "\\s+" :token :text}])
