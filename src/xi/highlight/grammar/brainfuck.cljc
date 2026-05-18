(ns xi.highlight.grammar.brainfuck)

(def brainfuck
  [   {:pattern "\\]" :token :text}
   {:pattern "[.,]+" :token :keyword}
   {:pattern "[+-]+" :token :name-builtin}
   {:pattern "[<>]+" :token :name-var}
   {:pattern "[^.,+\\-<>\\[\\]]+" :token :comment}])
