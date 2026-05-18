(ns xi.highlight.grammar.cheetah)

(def cheetah
  [   {:pattern "#[*](.|\\n)*?[*]#" :token :comment}
   {:pattern "#end[^#\\n]*(?:#|$)" :token :comment}
   {:pattern "#slurp$" :token :comment}
   {:pattern "\\s+" :token :text}])
