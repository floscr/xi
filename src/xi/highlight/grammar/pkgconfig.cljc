(ns xi.highlight.grammar.pkgconfig)

(def pkgconfig
  [   {:pattern "#.*$" :token :comment}
   {:pattern "[^${}#=:\\n.]+" :token :text}
   {:pattern "." :token :text}
   {:pattern "\\$\\$" :token :text}])
