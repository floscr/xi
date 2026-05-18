(ns xi.highlight.grammar.org-mode)

(def org-mode
  [   {:pattern "^# .*$" :token :comment}
   {:pattern "[{]{3}[^}]+[}]{3}" :token :name-builtin}
   {:pattern "\\n" :token :text}
   {:pattern "." :token :text}])
