(ns xi.highlight.grammar.django-jinja)

(def django-jinja
  [   {:pattern "[^{]+" :token :text}
   {:pattern "\\{[*#].*?[*#]\\}" :token :comment}
   {:pattern "\\{" :token :text}])
