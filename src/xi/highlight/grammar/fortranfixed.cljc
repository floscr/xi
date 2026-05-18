(ns xi.highlight.grammar.fortranfixed)

(def fortranfixed
  [   {:pattern "[C*].*\\n" :token :comment}
   {:pattern "#.*\\n" :token :comment}
   {:pattern " {0,4}!.*\\n" :token :comment}])
