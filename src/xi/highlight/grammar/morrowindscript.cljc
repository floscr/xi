(ns xi.highlight.grammar.morrowindscript)

(def morrowindscript
  [   {:pattern "\\s+" :token :text}
   {:pattern ";.*$" :token :comment}
   {:pattern "([\"'])(?:(?=(\\\\?))\\2.)*?\\1" :token :string}
   {:pattern "[0-9]+" :token :number}
   {:pattern "[0-9]+\\.[0-9]*(?!\\.)" :token :number}
   {:pattern "\\n" :token :text}
   {:pattern "\\S+\\s+" :token :text}
   {:pattern "[a-zA-Z0-9_]\\w*" :token :text}
   {:pattern "[\\w+]->[\\w+]" :token :operator}
   {:pattern "[()]" :token :punctuation}
   {:pattern "[#=,./%+\\-?]" :token :operator}
   {:pattern "(==|<=|<|>=|>|!=)" :token :operator}])
