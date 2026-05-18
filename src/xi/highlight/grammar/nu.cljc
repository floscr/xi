(ns xi.highlight.grammar.nu)

(def nu
  [   {:pattern "\\A#!.+\\n" :token :comment}
   {:pattern "#.*\\n" :token :comment}
   {:pattern "\\\\[\\w\\W]" :token :string}
   {:pattern "[\\[\\]{}()=]" :token :operator}
   {:pattern "<<<" :token :operator}
   {:pattern "&&|\\|\\|" :token :operator}
   {:pattern "\\$[a-zA-Z_]\\w*" :token :name-var}
   {:pattern ";" :token :punctuation}
   {:pattern "&" :token :punctuation}
   {:pattern "\\|" :token :punctuation}
   {:pattern "\\s+" :token :text}
   {:pattern "\\d+\\b" :token :number}
   {:pattern "<" :token :text}])
