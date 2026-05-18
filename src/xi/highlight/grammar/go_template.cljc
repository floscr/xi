(ns xi.highlight.grammar.go-template)

(def go-template
  [   {:pattern "{{(- )?/\\*(.|\\n)*?\\*/( -)?}}" :token :comment}
   {:pattern "[^{]+" :token :text}
   {:pattern "{" :token :text}])
