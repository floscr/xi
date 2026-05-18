(ns xi.highlight.grammar.erb)

(def erb
  [   {:pattern "[^<]+" :token :text}
   {:pattern "<" :token :text}])
