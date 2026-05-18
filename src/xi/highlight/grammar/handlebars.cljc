(ns xi.highlight.grammar.handlebars)

(def handlebars
  [   {:pattern "[^{]+" :token :text}
   {:pattern "\\{\\{!.*\\}\\}" :token :comment}])
