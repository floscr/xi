(ns xi.highlight.grammar.rust)

(def rust
  [   {:pattern "#![^[\\r\\n].*$" :token :comment}])
