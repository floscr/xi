(ns xi.highlight.grammar.objective-c)

(def objective-c
  [   {:pattern "\\n" :token :text}
   {:pattern "\\s+" :token :text}
   {:pattern "\\\\\\n" :token :text}
   {:pattern "//(\\n|[\\w\\W]*?[^\\\\]\\n)" :token :comment}
   {:pattern "/(\\\\\\n)?[*][\\w\\W]*?[*](\\\\\\n)?/" :token :comment}
   {:pattern "/(\\\\\\n)?[*][\\w\\W]*" :token :comment}])
