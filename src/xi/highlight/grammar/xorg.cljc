(ns xi.highlight.grammar.xorg)

(def xorg
  [   {:pattern "\\s+" :token :text}
   {:pattern "#.*$" :token :comment}
   {:pattern "(End(|Sub)Section)" :token :keyword}])
