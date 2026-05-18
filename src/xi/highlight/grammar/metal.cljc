(ns xi.highlight.grammar.metal)

(def metal
  [   {:pattern "\\n" :token :text}
   {:pattern "\\s+" :token :text}
   {:pattern "\\\\\\n" :token :text}
   {:pattern "//(\\n|[\\w\\W]*?[^\\\\]\\n)" :token :comment}
   {:pattern "/(\\\\\\n)?[*][\\w\\W]*?[*](\\\\\\n)?/" :token :comment}
   {:pattern "/(\\\\\\n)?[*][\\w\\W]*" :token :comment}])
