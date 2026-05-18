(ns xi.highlight.grammar.pacmanconf)

(def pacmanconf
  [   {:pattern "#.*$" :token :comment}
   {:pattern "^\\s*\\[.*?\\]\\s*$" :token :keyword}
   {:pattern "(\\$repo|\\$arch|%o|%u)\\b" :token :name-var}
   {:pattern "." :token :text}])
