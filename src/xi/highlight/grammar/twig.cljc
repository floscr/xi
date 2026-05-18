(ns xi.highlight.grammar.twig)

(def twig
  [   {:pattern "[^{]+" :token :text}
   {:pattern "\\{\\#.*?\\#\\}" :token :comment}
   {:pattern "\\{" :token :text}])
