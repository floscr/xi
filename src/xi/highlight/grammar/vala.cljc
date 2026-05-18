(ns xi.highlight.grammar.vala)

(def vala
  [   {:pattern "\\n" :token :text}
   {:pattern "\\s+" :token :text}
   {:pattern "\\\\\\n" :token :text}
   {:pattern "//(\\n|(.|\\n)*?[^\\\\]\\n)" :token :comment}
   {:pattern "/(\\\\\\n)?[*](.|\\n)*?[*](\\\\\\n)?/" :token :comment}])
