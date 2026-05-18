(ns xi.highlight.grammar.scdoc)

(def scdoc
  [   {:pattern "\\\\." :token :string}
   {:pattern "[^*_\\\\\\n]+" :token :text}
   {:pattern "\\n" :token :text}
   {:pattern "." :token :text}])
