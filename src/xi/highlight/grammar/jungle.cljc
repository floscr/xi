(ns xi.highlight.grammar.jungle)

(def jungle
  [   {:pattern "[^\\S\\n]+" :token :text}
   {:pattern "\\n" :token :text}
   {:pattern "#(\\n|[\\w\\W]*?[^#]\\n)" :token :comment}
   {:pattern "[\\.;\\[\\]\\(\\)\\$]" :token :punctuation}
   {:pattern "[a-zA-Z_]\\w*" :token :text}])
