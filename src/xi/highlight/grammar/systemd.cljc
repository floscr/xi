(ns xi.highlight.grammar.systemd)

(def systemd
  [   {:pattern "\\s+" :token :text}
   {:pattern "[;#].*" :token :comment}
   {:pattern "\\[.*?\\]$" :token :keyword}])
