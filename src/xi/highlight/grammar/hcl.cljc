(ns xi.highlight.grammar.hcl)

(def hcl
  [   {:pattern "[0-9]+" :token :number}
   {:pattern "[\\[\\](),.]" :token :punctuation}
   {:pattern "\\{" :token :text}
   {:pattern "\\}" :token :text}
   {:pattern "\\b(false|true)\\b" :token :keyword-type}
   {:pattern "\\s*#.*\\n" :token :comment}
   {:pattern "\\d+" :token :number}
   {:pattern "\\b\\w+\\b" :token :keyword}
   {:pattern "\\n" :token :text}
   {:pattern "\\s+" :token :text}
   {:pattern "\\\\\\n" :token :text}])
